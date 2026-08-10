package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactType;
import com.asterism.context.ContextHash;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.knowledge.SystemKnowledgeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MemoryCandidateService {
    private static final Logger log = LoggerFactory.getLogger(MemoryCandidateService.class);
    public static final String ARTIFACT_APPROVED = "ARTIFACT_APPROVED";
    public static final String CODING_COMPLETED = "CODING_COMPLETED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final Set<String> SOURCE_KINDS = Set.of(
            ARTIFACT_APPROVED, CODING_COMPLETED, VALIDATION_FAILED);
    private static final List<String> FORBIDDEN_CONTENT = List.of(
            "diff --git", "authorization: bearer", "api_key=", "apikey=", "password=", "secret=", "sk-",
            "session transcript", "chain of thought", "reasoning_content", "推理过程", "完整聊天记录");
    private static final List<String> UNCONFIRMED_CONTENT = List.of(
            "临时方案", "待定方案", "未确认讨论", "备选方案", "被否定方案",
            "待补充", "待确认", "todo:");

    private final MemoryCandidateRepository candidates;
    private final MemoryItemRepository memories;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SystemKnowledgeService knowledge;
    private final ArtifactService artifacts;
    private final DomainEventService events;

    public MemoryCandidateService(
            MemoryCandidateRepository candidates,
            MemoryItemRepository memories,
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            SystemKnowledgeService knowledge,
            ArtifactService artifacts,
            DomainEventService events) {
        this.candidates = candidates;
        this.memories = memories;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.knowledge = knowledge;
        this.artifacts = artifacts;
        this.events = events;
    }

    @Transactional
    public MemoryCandidate create(CandidateInput input) {
        return createOne(validateCandidate(input));
    }

    @Transactional
    public List<MemoryCandidate> createAll(List<CandidateInput> inputs) {
        var created = new ArrayList<MemoryCandidate>();
        for (var input : inputs == null ? List.<CandidateInput>of() : inputs) {
            try {
                created.add(createOne(validateCandidate(input)));
            } catch (IllegalArgumentException error) {
                // Memory 是 Artifact 的附加治理流程，候选不合法不能阻断主工作流。
                log.warn("跳过无效 Memory Candidate artifact={} source={} type={}",
                        input == null ? "" : input.artifactSourceId(),
                        input == null ? "" : input.sourceKind(),
                        error.getClass().getSimpleName());
            }
        }
        return List.copyOf(created);
    }

    @Transactional
    public MemoryItem approve(MemoryCandidate current, CandidateEdit edit, String actorId) {
        current = lockCandidate(current.candidateId());
        if (current.status() == MemoryCandidateStatus.CONFIRMED) {
            return memories.findByCandidateId(current.candidateId())
                    .orElseThrow(() -> new IllegalStateException("候选已确认但缺少 MemoryItem"));
        }
        if (current.status() != MemoryCandidateStatus.PENDING) {
            throw new IllegalStateException("只有待确认候选可以生成项目记忆");
        }
        var artifact = requireApprovalSource(current);
        var normalized = validateMemory(current, edit, artifact);
        var hash = normalizedHash(normalized.content());
        // Lambda 只捕获不会再变化的候选 ID，避免编译器拒绝捕获被重新赋值的 current。
        var candidateId = current.candidateId();
        memories.findByArtifactSourceIdAndMemoryTypeAndNormalizedContentHash(
                        current.artifactSourceId(), normalized.memoryType(), hash)
                .filter(item -> !item.candidateId().equals(candidateId))
                .ifPresent(item -> {
                    throw new IllegalArgumentException("当前 Artifact 已存在相同项目记忆");
                });
        var now = Instant.now();
        var memoryId = memoryId(current.candidateId());
        var memory = new MemoryItem(
                memoryId,
                current.systemId(),
                current.projectScope(),
                normalized.memoryType(),
                current.artifactSourceId(),
                normalized.title(),
                normalized.content(),
                normalized.confidence(),
                normalized.applicability(),
                normalized.expiresAt(),
                MemoryStatus.ACTIVE,
                current.candidateId(),
                current.candidateId(),
                "artifact:" + current.artifactSourceId(),
                current.evidenceRefs(),
                hash,
                current.sourceEventId(),
                actorId,
                metadata(current, artifact),
                current.createdBy(),
                now,
                now);
        insertMemory(memory);
        memory = memories.findByCandidateId(current.candidateId()).orElse(memory);
        replaceTargets(memory.memoryId(), current.systemId(), normalized.targetRefs());
        candidates.save(new MemoryCandidate(
                current.candidateId(), current.systemId(), current.projectScope(), normalized.memoryType(),
                current.artifactSourceId(), current.sourceKind(), normalized.title(), normalized.content(),
                normalized.confidence(), normalized.applicability(), normalized.expiresAt(),
                MemoryCandidateStatus.CONFIRMED, json(normalized.targetRefs()), current.evidenceRefs(), hash,
                current.sourceEventId(), current.createdBy(), actorId, "已确认并生成项目记忆",
                memory.memoryId(), current.createdAt(), now));
        appendEvent(DomainEventType.MemoryApproved, memory, actorId,
                "memory-approved:" + current.candidateId());
        log.info("Project Memory 已激活 memory={} candidate={} artifact={} type={}",
                memory.memoryId(), current.candidateId(), current.artifactSourceId(), memory.memoryType());
        return memory;
    }

    @Transactional
    public MemoryCandidate reject(MemoryCandidate current, String actorId, String note) {
        current = lockCandidate(current.candidateId());
        if (current.status() != MemoryCandidateStatus.PENDING) return current;
        var rejected = new MemoryCandidate(
                current.candidateId(), current.systemId(), current.projectScope(), current.memoryType(),
                current.artifactSourceId(), current.sourceKind(), current.title(), current.content(),
                current.confidence(), current.applicability(), current.expiresAt(),
                MemoryCandidateStatus.REJECTED, current.targetRefs(), current.evidenceRefs(),
                current.normalizedContentHash(), current.sourceEventId(), current.createdBy(), actorId,
                text(note).isBlank() ? "人工拒绝" : note.trim(), null, current.createdAt(), Instant.now());
        candidates.save(rejected);
        appendCandidateEvent(DomainEventType.MemoryRejected, rejected, actorId,
                "memory-rejected:" + rejected.candidateId());
        return rejected;
    }

    @Transactional
    public MemoryItem archive(MemoryItem current, String actorId) {
        if (current.status() == MemoryStatus.ARCHIVED) return current;
        var archived = copyWithStatus(current, MemoryStatus.ARCHIVED);
        memories.save(archived);
        appendEvent(DomainEventType.MemoryArchived, archived, actorId,
                "memory-archived:" + archived.memoryId());
        log.info("Project Memory 已归档 memory={} artifact={}",
                archived.memoryId(), archived.artifactSourceId());
        return archived;
    }

    @Transactional
    public void refreshArtifactStatuses(String rootArtifactId) {
        if (rootArtifactId == null || rootArtifactId.isBlank()) return;
        var memoryIds = jdbc.sql("""
                        select m.memory_id
                        from memory_items m
                        join artifacts source on source.artifact_id = m.artifact_source_id
                        where m.status = 'ACTIVE'
                          and source.root_artifact_id = :rootArtifactId
                          and (
                            source.status = 'SUPERSEDED'
                            or exists (
                              select 1 from artifacts replacement
                              where replacement.supersedes_artifact_id = source.artifact_id
                                and replacement.status = 'APPROVED'
                            )
                          )
                        order by m.memory_id
                        for update of m
                        """)
                .param("rootArtifactId", rootArtifactId)
                .query(String.class)
                .list();
        if (!memoryIds.isEmpty()) {
            jdbc.sql("""
                            update memory_items set status = 'OUTDATED'
                            where memory_id in (:memoryIds) and status = 'ACTIVE'
                            """)
                    .param("memoryIds", memoryIds)
                    .update();
            for (var memoryId : memoryIds) {
                memories.findById(memoryId).ifPresent(memory -> appendEvent(
                        DomainEventType.MemoryOutdated, memory, "system",
                        "memory-outdated:" + memoryId + ":" + rootArtifactId));
            }
        }
        var outdatedCandidates = jdbc.sql("""
                        update memory_candidates candidate
                        set status = 'OUTDATED', reviewed_by = 'system',
                            review_note = '来源 Artifact 已被有效新版本替代', reviewed_at = now()
                        from artifacts source
                        where candidate.artifact_source_id = source.artifact_id
                          and candidate.status = 'PENDING'
                          and source.root_artifact_id = :rootArtifactId
                          and (
                            source.status = 'SUPERSEDED'
                            or exists (
                              select 1 from artifacts replacement
                              where replacement.supersedes_artifact_id = source.artifact_id
                                and replacement.status = 'APPROVED'
                            )
                          )
                        """)
                .param("rootArtifactId", rootArtifactId)
                .update();
        if (!memoryIds.isEmpty() || outdatedCandidates > 0) {
            log.info("Artifact 替代已同步 Memory 状态 root={} memories={} candidates={}",
                    rootArtifactId, memoryIds.size(), outdatedCandidates);
        }
    }

    @Transactional
    public void refreshSystemArtifactStatuses(String systemId) {
        var roots = jdbc.sql("""
                        select distinct root_artifact_id
                        from artifacts
                        where system_id = :systemId
                        order by root_artifact_id
                        """)
                .param("systemId", systemId)
                .query(String.class)
                .list();
        roots.forEach(this::refreshArtifactStatuses);
    }

    @Transactional
    public void outdateRejectedCodingCandidate(String artifactId) {
        var changed = jdbc.sql("""
                        update memory_candidates
                        set status = 'OUTDATED', reviewed_by = 'system',
                            review_note = 'CodingArtifact 未通过验证', reviewed_at = now()
                        where artifact_source_id = :artifactId
                          and source_kind = 'CODING_COMPLETED'
                          and status = 'PENDING'
                        """)
                .param("artifactId", artifactId)
                .update();
        if (changed > 0) {
            log.info("未验证代码经验候选已失效 artifact={} candidates={}", artifactId, changed);
        }
    }

    @Transactional
    public void archiveExpired(String systemId) {
        var changed = jdbc.sql("""
                        update memory_items
                        set status = 'ARCHIVED'
                        where system_id = :systemId and status = 'ACTIVE'
                          and expires_at is not null and expires_at <= now()
                        """)
                .param("systemId", systemId)
                .update();
        if (changed > 0) log.info("到期 Project Memory 已归档 system={} count={}", systemId, changed);
    }

    public Map<String, List<String>> targetRefs(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) return Map.of();
        var ids = items.stream().map(MemoryItem::memoryId).toList();
        var values = new LinkedHashMap<String, List<String>>();
        jdbc.sql("""
                        select memory_id, knowledge_entry_id from memory_targets
                        where memory_id in (:ids)
                        order by memory_id, knowledge_entry_id
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> Map.entry(rs.getString("memory_id"), rs.getString("knowledge_entry_id")))
                .list()
                .forEach(value -> values.computeIfAbsent(value.getKey(), key -> new ArrayList<>()).add(value.getValue()));
        return values;
    }

    public List<String> targetRefs(MemoryCandidate candidate) {
        return readList(candidate.targetRefs());
    }

    public List<String> evidenceRefs(MemoryCandidate candidate) {
        return readList(candidate.evidenceRefs());
    }

    public List<String> evidenceRefs(MemoryItem memory) {
        return readList(memory.evidenceRefs());
    }

    private MemoryCandidate createOne(CandidateInput input) {
        var hash = normalizedHash(input.content());
        var existing = candidates
                .findBySystemIdAndArtifactSourceIdAndSourceKindAndMemoryTypeAndNormalizedContentHash(
                        input.systemId(), input.artifactSourceId(), input.sourceKind(), input.memoryType(), hash)
                .orElse(null);
        if (existing != null) return existing;
        var candidateId = candidateId(input, hash);
        var candidate = new MemoryCandidate(
                candidateId, input.systemId(), input.projectScope(), input.memoryType(),
                input.artifactSourceId(), input.sourceKind(), input.title(), input.content(),
                input.confidence(), input.applicability(), input.expiresAt(), MemoryCandidateStatus.PENDING,
                json(input.targetRefs()), json(input.evidenceRefs()), hash, input.sourceEventId(),
                input.createdBy(), null, null, null, Instant.now(), null);
        if (insertCandidate(candidate)) {
            appendCandidateEvent(DomainEventType.MemoryCandidateCreated, candidate, input.createdBy(),
                    "memory-candidate:" + candidateId);
            log.info("Memory Extractor 候选已写入 candidate={} artifact={} type={} confidence={}",
                    candidateId, candidate.artifactSourceId(), candidate.memoryType(), candidate.confidence());
            return candidate;
        }
        return candidates.findById(candidateId)
                .orElseThrow(() -> new IllegalStateException("Memory Candidate 并发去重后未找到记录"));
    }

    private MemoryCandidate lockCandidate(String candidateId) {
        return candidates.lockById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Memory Candidate 不存在"));
    }

    private CandidateInput validateCandidate(CandidateInput input) {
        if (input == null) throw new IllegalArgumentException("Memory Candidate 不能为空");
        var artifact = artifacts.require(input.artifactSourceId());
        var systemId = text(input.systemId()).trim();
        var projectScope = text(input.projectScope()).trim();
        var sourceKind = text(input.sourceKind()).trim();
        var title = text(input.title()).trim();
        var content = text(input.content()).trim();
        if (!artifact.systemId().equals(systemId)) throw new IllegalArgumentException("Artifact 不属于当前项目");
        if (projectScope.isBlank()) projectScope = systemId;
        if (!projectScope.equals(systemId)) throw new IllegalArgumentException("项目范围必须与当前系统一致");
        if (input.memoryType() == null) throw new IllegalArgumentException("缺少 Memory 类型");
        if (!SOURCE_KINDS.contains(sourceKind)) throw new IllegalArgumentException("不支持的 Memory 来源");
        validateSourceMapping(artifact, input.memoryType(), sourceKind);
        validateContent(title, content, input.confidence(), input.applicability(), false);
        var targets = distinct(input.targetRefs());
        targets.forEach(target -> knowledge.require(systemId, target));
        return new CandidateInput(
                systemId, projectScope, input.memoryType(), artifact.artifactId(), sourceKind,
                title, content, input.confidence(), input.applicability(), input.expiresAt(),
                targets, distinct(input.evidenceRefs()), text(input.sourceEventId()),
                text(input.createdBy()).isBlank() ? "system" : input.createdBy());
    }

    private CandidateEdit validateMemory(MemoryCandidate current, CandidateEdit edit, Artifact artifact) {
        if (edit == null) {
            edit = new CandidateEdit(
                    current.memoryType(), current.title(), current.content(), current.confidence(),
                    current.applicability(), current.expiresAt(), readList(current.targetRefs()));
        }
        if (edit.memoryType() == null) throw new IllegalArgumentException("缺少 Memory 类型");
        // 审核可以在同一 Artifact 允许的类型内纠正分类，不能改变来源语义。
        validateSourceMapping(artifact, edit.memoryType(), current.sourceKind());
        var title = text(edit.title()).trim();
        var content = text(edit.content()).trim();
        validateContent(title, content, edit.confidence(), edit.applicability(), true);
        if (current.sourceKind().equals(VALIDATION_FAILED)
                && (!containsAny(content, List.of("原因", "根因", "由于"))
                || !containsAny(content, List.of("解决", "修复", "避免", "处理")))) {
            throw new IllegalArgumentException("问题经验需补充根因和已验证的解决或避免方式");
        }
        var targets = distinct(edit.targetRefs());
        targets.forEach(target -> knowledge.require(current.systemId(), target));
        return new CandidateEdit(
                edit.memoryType(), title, content, edit.confidence(), edit.applicability(),
                edit.expiresAt(), targets);
    }

    private Artifact requireApprovalSource(MemoryCandidate current) {
        var artifact = artifacts.require(current.artifactSourceId());
        if (!artifact.systemId().equals(current.systemId())) {
            throw new IllegalStateException("Memory Candidate 与 Artifact 项目不一致");
        }
        if (current.sourceKind().equals(VALIDATION_FAILED)) {
            var currentValidationFailure = artifact.artifactType() == ArtifactType.VALIDATION
                    && artifact.status() == ArtifactStatus.APPROVED
                    && Set.of("FAILED", "ERROR").contains(artifact.content().path("result").asText());
            var legacyCodingFailure = artifact.artifactType() == ArtifactType.CODING
                    && Set.of(ArtifactStatus.REJECTED, ArtifactStatus.SUPERSEDED).contains(artifact.status());
            if (!currentValidationFailure && !legacyCodingFailure) {
                throw new IllegalStateException("验证失败经验的 Artifact 状态已变化");
            }
            return artifact;
        }
        if (artifact.status() != ArtifactStatus.APPROVED) {
            throw new IllegalStateException("来源 Artifact 尚未批准或验证通过");
        }
        return artifact;
    }

    private void validateSourceMapping(Artifact artifact, MemoryType type, String sourceKind) {
        if (sourceKind.equals(ARTIFACT_APPROVED) && artifact.status() != ArtifactStatus.APPROVED) {
            throw new IllegalArgumentException("只有 Approved Artifact 可以提取正式知识候选");
        }
        if (sourceKind.equals(ARTIFACT_APPROVED)
                && artifact.artifactType() == ArtifactType.PRODUCT && type != MemoryType.FACT) {
            throw new IllegalArgumentException("ProductArtifact 只生成业务事实候选");
        }
        if (sourceKind.equals(ARTIFACT_APPROVED)
                && artifact.artifactType() == ArtifactType.PLANNING
                && !Set.of(MemoryType.DECISION, MemoryType.CONSTRAINT).contains(type)) {
            throw new IllegalArgumentException("PlanningArtifact 只生成技术决策或约束候选");
        }
        if (sourceKind.equals(CODING_COMPLETED)
                && (artifact.artifactType() != ArtifactType.CODING
                || !Set.of(ArtifactStatus.PROPOSED, ArtifactStatus.APPROVED).contains(artifact.status())
                || type != MemoryType.EXPERIENCE)) {
            throw new IllegalArgumentException("CodingArtifact Completed 只生成代码经验候选");
        }
        if (sourceKind.equals(VALIDATION_FAILED)
                && type == MemoryType.EXPERIENCE) {
            var currentValidationFailure = artifact.artifactType() == ArtifactType.VALIDATION
                    && artifact.status() == ArtifactStatus.APPROVED
                    && Set.of("FAILED", "ERROR").contains(artifact.content().path("result").asText());
            var legacyCodingFailure = artifact.artifactType() == ArtifactType.CODING
                    && Set.of(ArtifactStatus.REJECTED, ArtifactStatus.SUPERSEDED).contains(artifact.status());
            if (currentValidationFailure || legacyCodingFailure) return;
        }
        if (sourceKind.equals(VALIDATION_FAILED)) {
            throw new IllegalArgumentException("Validation Failed 只生成问题经验候选");
        }
    }

    private void validateContent(
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            boolean confirmed) {
        if (title.isBlank() || title.length() > 80) throw new IllegalArgumentException("Memory 标题不合法");
        var completeText = title + "\n" + content;
        if (content.isBlank() || content.length() > 1000 || containsForbiddenContent(completeText)) {
            throw new IllegalArgumentException("Memory 正文不适合长期保存");
        }
        if (confirmed && UNCONFIRMED_CONTENT.stream()
                .anyMatch(value -> completeText.toLowerCase(Locale.ROOT).contains(value))) {
            throw new IllegalArgumentException("未确认、临时或被否定内容不能进入项目记忆");
        }
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("置信度必须在 0 到 1 之间");
        if (applicability == null) throw new IllegalArgumentException("缺少适用范围");
    }

    private boolean insertCandidate(MemoryCandidate candidate) {
        return jdbc.sql("""
                        insert into memory_candidates(
                            candidate_id, system_id, project_scope, memory_type, artifact_source_id,
                            source_kind, title, content, confidence, applicability, expires_at, status,
                            target_refs, evidence_refs, normalized_content_hash, source_event_id,
                            created_by, created_at
                        ) values (
                            :candidateId, :systemId, :projectScope, :memoryType, :artifactSourceId,
                            :sourceKind, :title, :content, :confidence, :applicability, :expiresAt, :status,
                            cast(:targetRefs as jsonb), cast(:evidenceRefs as jsonb), :normalizedContentHash,
                            :sourceEventId, :createdBy, :createdAt
                        )
                        on conflict do nothing
                        """)
                .param("candidateId", candidate.candidateId())
                .param("systemId", candidate.systemId())
                .param("projectScope", candidate.projectScope())
                .param("memoryType", candidate.memoryType().name())
                .param("artifactSourceId", candidate.artifactSourceId())
                .param("sourceKind", candidate.sourceKind())
                .param("title", candidate.title())
                .param("content", candidate.content())
                .param("confidence", candidate.confidence())
                .param("applicability", candidate.applicability().name())
                .param("expiresAt", candidate.expiresAt() == null ? null : Timestamp.from(candidate.expiresAt()))
                .param("status", candidate.status().name())
                .param("targetRefs", candidate.targetRefs())
                .param("evidenceRefs", candidate.evidenceRefs())
                .param("normalizedContentHash", candidate.normalizedContentHash())
                .param("sourceEventId", candidate.sourceEventId())
                .param("createdBy", candidate.createdBy())
                .param("createdAt", Timestamp.from(candidate.createdAt()))
                .update() == 1;
    }

    private void insertMemory(MemoryItem memory) {
        var inserted = jdbc.sql("""
                        insert into memory_items(
                            memory_id, system_id, project_scope, memory_type, artifact_source_id,
                            title, content, confidence, applicability, expires_at, status, candidate_id,
                            stable_candidate_id, source_ref, evidence_refs, normalized_content_hash,
                            source_event_id, approved_by, metadata_json, created_by, created_at, approved_at
                        ) values (
                            :memoryId, :systemId, :projectScope, :memoryType, :artifactSourceId,
                            :title, :content, :confidence, :applicability, :expiresAt, :status, :candidateId,
                            :stableCandidateId, :sourceRef, cast(:evidenceRefs as jsonb), :normalizedContentHash,
                            :sourceEventId, :approvedBy, cast(:metadataJson as jsonb), :createdBy, :createdAt, :approvedAt
                        )
                        on conflict (memory_id) do nothing
                        """)
                .param("memoryId", memory.memoryId())
                .param("systemId", memory.systemId())
                .param("projectScope", memory.projectScope())
                .param("memoryType", memory.memoryType().name())
                .param("artifactSourceId", memory.artifactSourceId())
                .param("title", memory.title())
                .param("content", memory.content())
                .param("confidence", memory.confidence())
                .param("applicability", memory.applicability().name())
                .param("expiresAt", memory.expiresAt() == null ? null : Timestamp.from(memory.expiresAt()))
                .param("status", memory.status().name())
                .param("candidateId", memory.candidateId())
                .param("stableCandidateId", memory.stableCandidateId())
                .param("sourceRef", memory.sourceRef())
                .param("evidenceRefs", memory.evidenceRefs())
                .param("normalizedContentHash", memory.normalizedContentHash())
                .param("sourceEventId", memory.sourceEventId())
                .param("approvedBy", memory.approvedBy())
                .param("metadataJson", memory.metadataJson())
                .param("createdBy", memory.createdBy())
                .param("createdAt", Timestamp.from(memory.createdAt()))
                .param("approvedAt", Timestamp.from(memory.approvedAt()))
                .update();
        if (inserted == 0 && memories.findByCandidateId(memory.candidateId()).isEmpty()) {
            throw new IllegalStateException("Project Memory 并发写入后未找到记录");
        }
    }

    private void replaceTargets(String memoryId, String systemId, List<String> targetRefs) {
        jdbc.sql("delete from memory_targets where memory_id = :memoryId")
                .param("memoryId", memoryId)
                .update();
        for (var target : targetRefs) {
            // 审核时再次确认目标属于当前项目，关系表只保存稳定知识 ID。
            knowledge.require(systemId, target);
            jdbc.sql("""
                            insert into memory_targets(memory_id, knowledge_entry_id, created_at)
                            values (:memoryId, :target, :createdAt)
                            """)
                    .param("memoryId", memoryId)
                    .param("target", target)
                    .param("createdAt", Timestamp.from(Instant.now()))
                    .update();
        }
    }

    private void appendCandidateEvent(
            DomainEventType type, MemoryCandidate candidate, String actorId, String key) {
        events.append(new DomainEventService.AppendEvent(
                type, candidate.systemId(), null, null, null, actorId, "control-plane",
                Map.of(
                        "candidateId", candidate.candidateId(),
                        "artifactSourceId", candidate.artifactSourceId() == null ? "" : candidate.artifactSourceId(),
                        "memoryType", candidate.memoryType().name()),
                candidate.candidateId(), candidate.sourceEventId(), key));
    }

    private void appendEvent(DomainEventType type, MemoryItem memory, String actorId, String key) {
        var artifact = memory.artifactSourceId() == null ? null : artifacts.require(memory.artifactSourceId());
        events.append(new DomainEventService.AppendEvent(
                type, memory.systemId(),
                artifact == null ? null : artifact.caseId(),
                artifact == null ? null : artifact.prdId(),
                artifact == null ? null : artifact.workItemId(),
                actorId, "control-plane",
                Map.of(
                        "memoryId", memory.memoryId(),
                        "candidateId", memory.candidateId() == null ? "" : memory.candidateId(),
                        "artifactSourceId", memory.artifactSourceId() == null ? "" : memory.artifactSourceId(),
                        "memoryType", memory.memoryType().name(),
                        "status", memory.status().name()),
                memory.memoryId(), memory.sourceEventId(), key));
    }

    private MemoryItem copyWithStatus(MemoryItem current, MemoryStatus status) {
        return new MemoryItem(
                current.memoryId(), current.systemId(), current.projectScope(), current.memoryType(),
                current.artifactSourceId(), current.title(), current.content(), current.confidence(),
                current.applicability(), current.expiresAt(), status, current.candidateId(),
                current.stableCandidateId(), current.sourceRef(), current.evidenceRefs(),
                current.normalizedContentHash(), current.sourceEventId(), current.approvedBy(),
                current.metadataJson(), current.createdBy(), current.createdAt(), current.approvedAt());
    }

    private String candidateId(CandidateInput input, String hash) {
        var identity = String.join("|",
                input.systemId(), input.artifactSourceId(), input.sourceKind(), input.memoryType().name(), hash);
        return "candidate-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private String memoryId(String candidateId) {
        return "mem-" + UUID.nameUUIDFromBytes(candidateId.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizedHash(String content) {
        var normalized = Normalizer.normalize(content, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return ContextHash.sha256(normalized);
    }

    private boolean containsForbiddenContent(String content) {
        var value = content.toLowerCase(Locale.ROOT);
        return FORBIDDEN_CONTENT.stream().anyMatch(value::contains);
    }

    private boolean containsAny(String content, List<String> values) {
        return values.stream().anyMatch(content::contains);
    }

    private String metadata(MemoryCandidate candidate, Artifact artifact) {
        return json(Map.of(
                "candidateId", candidate.candidateId(),
                "sourceKind", candidate.sourceKind(),
                "artifactType", artifact.artifactType().name(),
                "artifactVersion", artifact.version(),
                "workItemId", artifact.workItemId()));
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Memory 数据不是合法 JSON", error);
        }
    }

    private List<String> distinct(List<String> values) {
        var result = new LinkedHashSet<String>();
        if (values != null) values.stream().map(this::text).map(String::trim)
                .filter(value -> !value.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record CandidateInput(
            String systemId,
            String projectScope,
            MemoryType memoryType,
            String artifactSourceId,
            String sourceKind,
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            Instant expiresAt,
            List<String> targetRefs,
            List<String> evidenceRefs,
            String sourceEventId,
            String createdBy) {
    }

    public record CandidateEdit(
            MemoryType memoryType,
            String title,
            String content,
            double confidence,
            MemoryApplicability applicability,
            Instant expiresAt,
            List<String> targetRefs) {
    }
}
