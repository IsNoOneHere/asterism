package com.asterism.memory;

import com.asterism.context.ContextHash;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.knowledge.SystemKnowledgeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
    private static final Set<String> CATEGORIES = Set.of("constraint", "convention", "lesson");
    private static final Set<String> AUDIENCES = Set.of("product", "execution", "both");
    private static final List<String> FORBIDDEN_CONTENT = List.of(
            "diff --git", "authorization: bearer", "api_key=", "apikey=", "password=", "secret=", "sk-");

    private final MemoryItemRepository memories;
    private final JdbcAggregateTemplate aggregate;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SystemKnowledgeService knowledge;
    private final DomainEventService events;

    public MemoryCandidateService(MemoryItemRepository memories, JdbcAggregateTemplate aggregate, JdbcClient jdbc,
                                  ObjectMapper objectMapper, SystemKnowledgeService knowledge,
                                  DomainEventService events) {
        this.memories = memories;
        this.aggregate = aggregate;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.knowledge = knowledge;
        this.events = events;
    }

    @Transactional
    public MemoryItem create(CandidateInput input) {
        return createOne(validate(input));
    }

    @Transactional
    public List<MemoryItem> createAll(List<CandidateInput> inputs) {
        var created = new ArrayList<MemoryItem>();
        for (var input : inputs == null ? List.<CandidateInput>of() : inputs) {
            try {
                created.add(createOne(validate(input)));
            } catch (IllegalArgumentException error) {
                // Agent 候选不能阻断 PRD 确认或发布完成，只记录类型与来源。
                log.warn("跳过无效记忆候选 source={} type={}", input == null ? "" : input.sourceRef(),
                        error.getClass().getSimpleName());
            }
        }
        return List.copyOf(created);
    }

    @Transactional
    public MemoryItem approve(MemoryItem current, CandidateEdit edit, String actorId) {
        var input = validate(new CandidateInput(
                current.systemId(), edit.category(), edit.audience(), edit.title(), edit.content(),
                current.sourceRef(), edit.targetRefs(), readList(current.evidenceRefs()),
                workItemId(current), current.sourceEventId(), current.createdBy()));
        var hash = normalizedHash(input.content());
        memories.findBySystemIdAndNormalizedContentHash(current.systemId(), hash)
                .filter(item -> !item.memoryId().equals(current.memoryId()))
                .ifPresent(item -> {
                    throw new IllegalArgumentException("相同内容的系统记忆已存在");
                });
        var approved = new MemoryItem(
                current.memoryId(), current.systemId(), input.content(), "approved", input.audience(),
                current.stableCandidateId(), current.sourceRef(), current.evidenceRefs(), hash,
                current.sourceEventId(), actorId, metadata(input), current.createdBy(), current.createdAt(), Instant.now());
        aggregate.update(approved);
        replaceTargets(approved.memoryId(), input.systemId(), input.targetRefs());
        appendEvent(DomainEventType.MemoryApproved, approved, actorId, "memory-approved:" + approved.memoryId());
        return approved;
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

    public List<String> evidenceRefs(MemoryItem memory) {
        return readList(memory.evidenceRefs());
    }

    private MemoryItem createOne(CandidateInput input) {
        var hash = normalizedHash(input.content());
        var existing = existing(input.systemId(), input.sourceRef(), hash);
        if (existing != null) return existing;
        var stableId = stableCandidateId(input.systemId(), input.sourceRef());
        var sourceRef = input.sourceRef().isBlank() ? "manual:" + stableId : input.sourceRef();
        var memory = new MemoryItem(
                "mem-" + stableId.substring("candidate-".length()), input.systemId(), input.content(), "candidate",
                input.audience(), stableId, sourceRef, json(input.evidenceRefs()), hash,
                input.sourceEventId(), null, metadata(input), input.createdBy(), Instant.now(), null);
        if (insertCandidate(memory)) {
            replaceTargets(memory.memoryId(), input.systemId(), input.targetRefs());
            appendEvent(DomainEventType.MemoryCandidateCreated, memory, input.createdBy(),
                    "memory-candidate:" + stableId);
            log.info("记忆 candidate 已写入 system={} candidate={} source={} targets={}",
                    memory.systemId(), stableId, sourceRef, input.targetRefs().size());
            return memory;
        }
        // ON CONFLICT 会等待并发写入提交，不会把当前事务标记为 rollback-only。
        var concurrent = existing(input.systemId(), input.sourceRef(), hash);
        if (concurrent != null) return concurrent;
        throw new IllegalStateException("记忆候选并发去重后未找到已有记录");
    }

    private boolean insertCandidate(MemoryItem memory) {
        return jdbc.sql("""
                        insert into memory_items(
                            memory_id, system_id, content, status, audience, stable_candidate_id,
                            source_ref, evidence_refs, normalized_content_hash, source_event_id,
                            metadata_json, created_by, created_at
                        ) values (
                            :memoryId, :systemId, :content, :status, :audience, :stableCandidateId,
                            :sourceRef, cast(:evidenceRefs as jsonb), :normalizedContentHash, :sourceEventId,
                            cast(:metadataJson as jsonb), :createdBy, :createdAt
                        )
                        on conflict do nothing
                        """)
                .param("memoryId", memory.memoryId())
                .param("systemId", memory.systemId())
                .param("content", memory.content())
                .param("status", memory.status())
                .param("audience", memory.audience())
                .param("stableCandidateId", memory.stableCandidateId())
                .param("sourceRef", memory.sourceRef())
                .param("evidenceRefs", memory.evidenceRefs())
                .param("normalizedContentHash", memory.normalizedContentHash())
                .param("sourceEventId", memory.sourceEventId())
                .param("metadataJson", memory.metadataJson())
                .param("createdBy", memory.createdBy())
                .param("createdAt", memory.createdAt())
                .update() == 1;
    }

    private MemoryItem existing(String systemId, String sourceRef, String hash) {
        if (!sourceRef.isBlank()) {
            var bySource = memories.findBySystemIdAndSourceRef(systemId, sourceRef);
            if (bySource.isPresent()) return bySource.get();
        }
        return memories.findBySystemIdAndNormalizedContentHash(systemId, hash).orElse(null);
    }

    private CandidateInput validate(CandidateInput input) {
        if (input == null) throw new IllegalArgumentException("记忆候选不能为空");
        var category = text(input.category());
        var audience = text(input.audience()).isBlank() ? "both" : input.audience().trim();
        var title = text(input.title()).trim();
        var content = text(input.content()).trim();
        if (input.systemId() == null || input.systemId().isBlank()) throw new IllegalArgumentException("系统不能为空");
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("不支持的记忆类型");
        if (!AUDIENCES.contains(audience)) throw new IllegalArgumentException("不支持的记忆适用阶段");
        if (title.isBlank() || title.length() > 80) throw new IllegalArgumentException("记忆标题不合法");
        if (content.isBlank() || content.length() > 1000 || containsForbiddenContent(content)) {
            throw new IllegalArgumentException("记忆正文不适合长期保存");
        }
        var targets = distinct(input.targetRefs());
        targets.forEach(target -> knowledge.require(input.systemId(), target));
        var evidence = distinct(input.evidenceRefs());
        var sourceRef = text(input.sourceRef()).trim();
        var actor = text(input.createdBy()).isBlank() ? "system" : input.createdBy();
        return new CandidateInput(input.systemId(), category, audience, title, content, sourceRef, targets,
                evidence, text(input.workItemId()), text(input.sourceEventId()), actor);
    }

    private void replaceTargets(String memoryId, String systemId, List<String> targetRefs) {
        jdbc.sql("delete from memory_targets where memory_id = :memoryId")
                .param("memoryId", memoryId)
                .update();
        for (var target : targetRefs) {
            // validate 已确认目标属于当前系统，关系表只保存稳定 knowledge_entry_id。
            knowledge.require(systemId, target);
            jdbc.sql("""
                            insert into memory_targets(memory_id, knowledge_entry_id, created_at)
                            values (:memoryId, :target, :createdAt)
                            """)
                    .param("memoryId", memoryId)
                    .param("target", target)
                    .param("createdAt", Instant.now())
                    .update();
        }
    }

    private void appendEvent(DomainEventType type, MemoryItem memory, String actorId, String key) {
        events.append(new DomainEventService.AppendEvent(
                type, memory.systemId(), null, null, workItemId(memory), actorId, "control-plane",
                Map.of("memoryId", memory.memoryId(), "stableCandidateId", memory.stableCandidateId(),
                        "sourceRef", memory.sourceRef()),
                memory.stableCandidateId(), memory.sourceEventId(), key));
    }

    private String stableCandidateId(String systemId, String sourceRef) {
        var identity = sourceRef.isBlank() ? UUID.randomUUID().toString() : systemId + "|" + sourceRef;
        return "candidate-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
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

    private String metadata(CandidateInput input) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("category", input.category());
        metadata.put("title", input.title());
        if (!input.workItemId().isBlank()) metadata.put("workItemId", input.workItemId());
        return json(metadata);
    }

    private String workItemId(MemoryItem memory) {
        try {
            Map<String, Object> metadata = objectMapper.readValue(memory.metadataJson(), new TypeReference<>() {
            });
            return text(metadata.get("workItemId"));
        } catch (JsonProcessingException error) {
            return "";
        }
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
            throw new IllegalArgumentException("记忆候选不是合法 JSON", error);
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
            String category,
            String audience,
            String title,
            String content,
            String sourceRef,
            List<String> targetRefs,
            List<String> evidenceRefs,
            String workItemId,
            String sourceEventId,
            String createdBy) {
    }

    public record CandidateEdit(
            String category,
            String audience,
            String title,
            String content,
            List<String> targetRefs) {
    }
}
