package com.asterism.artifact;

import com.asterism.context.ContextHash;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ArtifactService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    private static final Set<String> FORBIDDEN_CONTENT_KEYS = Set.of(
            "apikey", "api_key", "accesstoken", "access_token", "secrettoken", "secret_token",
            "sessionid", "session_id", "tokenusage", "token_usage", "subagentruns", "subagent_runs",
            "validationresults", "validation_results", "sessiontranscript", "session_transcript",
            "transcript", "hiddenthought", "hidden_thought", "chainofthought", "chain_of_thought",
            "reasoningcontent", "reasoning_content");
    private static final List<String> FORBIDDEN_CONTENT_VALUES = List.of(
            "authorization: bearer ", "api_key=", "apikey=", "access_token=", "secret_token=",
            "password=", "-----begin private key-----", "ghp_", "glpat-", "sk-");

    private final ArtifactRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;

    public ArtifactService(ArtifactRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    public Mutation createApprovedProduct(Metadata metadata, ProductArtifactContent content,
                                          ArtifactRef supersedesRef, ArtifactRef expectedHead,
                                          String idempotencyKey) {
        var contentJson = contentJson(ArtifactType.PRODUCT, content);
        var contentHash = ContextHash.sha256(contentJson);
        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), ArtifactType.PRODUCT, metadata, null,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var scopeLock = "product:" + metadata.systemId() + ":" + metadata.prdId();
        repository.lockVersion(scopeLock, ArtifactType.PRODUCT);
        var artifactId = "art-" + UUID.randomUUID();
        var now = Instant.now();
        var rootArtifactId = repository.findRoot(metadata.systemId(), metadata.prdId()).orElse(null);
        if (rootArtifactId == null) {
            rootArtifactId = artifactId;
            repository.insertRoot(rootArtifactId, metadata.systemId(), metadata.prdId(), now);
        }
        repository.lockVersion(rootArtifactId, ArtifactType.PRODUCT);
        // 并发请求等待版本锁后，必须复用先提交的同一幂等结果。
        existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), ArtifactType.PRODUCT, metadata, null,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var previousHead = expectHead(rootArtifactId, ArtifactType.PRODUCT, expectedHead);
        var supersedes = validateSupersedes(
                ArtifactType.PRODUCT, rootArtifactId, metadata, supersedesRef);
        if (!Objects.equals(
                previousHead == null ? null : previousHead.artifactId(),
                supersedes == null ? null : supersedes.artifactId())) {
            throw new ArtifactConflictException("Product supersedes 必须与 expectedHead 精确一致");
        }
        var version = repository.nextVersion(rootArtifactId, ArtifactType.PRODUCT);
        insert(artifactId, ArtifactType.PRODUCT, rootArtifactId, metadata, version, ArtifactStatus.APPROVED,
                null, supersedes == null ? null : supersedes.artifactId(),
                previousHead == null ? null : previousHead.artifactId(), contentJson, contentHash,
                idempotencyKey, now, metadata.actorId(), now, "PRD 已确认");
        var created = require(artifactId);
        updateHead(rootArtifactId, ArtifactType.PRODUCT, previousHead, created, now);
        if (previousHead != null) supersedePreviousHead(previousHead, created, metadata.actorId(), now);
        log.info("ProductArtifact 已创建并设为 Head artifactId={} version={}", artifactId, version);
        return new Mutation(require(artifactId), previousHead);
    }

    @Transactional
    public Mutation createProposal(ArtifactType type, Metadata metadata, ArtifactRef parentRef,
                                   ArtifactRef supersedesRef, ArtifactRef expectedHead,
                                   ArtifactContent content, String idempotencyKey) {
        if (type != ArtifactType.PLANNING && type != ArtifactType.CODING) {
            throw new IllegalArgumentException(type + " 不是人工审核 Proposal");
        }
        var parent = requireEffectiveApproved(parentRef);
        validateParentType(type, parent);
        validateScope(metadata, parent);
        var contentJson = contentJson(type, content);
        var contentHash = ContextHash.sha256(contentJson);
        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), type, metadata, parentRef,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var rootArtifactId = parent.rootArtifactId();
        repository.lockVersion(rootArtifactId, type);
        // 等待版本锁期间父 Head 可能被人工切换，落库前必须再次核对完整有效父链。
        parent = requireEffectiveApproved(parentRef);
        validateParentType(type, parent);
        validateScope(metadata, parent);
        // 并发请求等待版本锁后，必须复用先提交的同一幂等结果。
        existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), type, metadata, parentRef,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var currentHead = expectHead(rootArtifactId, type, expectedHead);
        var supersedes = validateSupersedes(type, rootArtifactId, metadata, supersedesRef);
        if (supersedes != null && supersedes.status() == ArtifactStatus.PROPOSED
                && !Objects.equals(
                supersedes.expectedHeadArtifactId(),
                currentHead == null ? null : currentHead.artifactId())) {
            throw new ArtifactConflictException("被替代 Proposal 的 expectedHead 与新 Proposal 不一致");
        }
        var artifactId = "art-" + UUID.randomUUID();
        var now = Instant.now();
        var version = repository.nextVersion(rootArtifactId, type);
        insert(artifactId, type, rootArtifactId, metadata, version, ArtifactStatus.PROPOSED,
                parent.artifactId(), supersedes == null ? null : supersedes.artifactId(),
                currentHead == null ? null : currentHead.artifactId(),
                contentJson, contentHash, idempotencyKey, now, null, null, null);
        if (supersedes != null && supersedes.status() == ArtifactStatus.PROPOSED
                && repository.transitionStatus(
                supersedes.artifactId(), ArtifactStatus.PROPOSED, ArtifactStatus.SUPERSEDED,
                metadata.actorId(), now, "已由 " + artifactId + " 替代") != 1) {
            throw new ArtifactConflictException("被替代 Proposal 状态已变化");
        }
        log.info("Artifact Proposal 已创建 type={} artifactId={} version={} parent={} supersedes={} expectedHead={}",
                type, artifactId, version, parent.artifactId(),
                supersedes == null ? null : supersedes.artifactId(),
                currentHead == null ? null : currentHead.artifactId());
        return new Mutation(require(artifactId), currentHead);
    }

    /** Validation/Release 是系统完成事实，直接以 Approved 结果物化，不经过 Agent 审批。 */
    @Transactional
    public Mutation createApprovedResult(ArtifactType type, Metadata metadata, ArtifactRef parentRef,
                                         ArtifactRef supersedesRef, ArtifactRef expectedHead,
                                         ArtifactContent content, String idempotencyKey, String note) {
        if (type != ArtifactType.VALIDATION && type != ArtifactType.RELEASE) {
            throw new IllegalArgumentException(type + " 不是系统结果 Artifact");
        }
        var parent = requireEffectiveApproved(parentRef);
        validateParentType(type, parent);
        validateScope(metadata, parent);
        if (type == ArtifactType.RELEASE) requirePassedValidation(parent);
        var contentJson = contentJson(type, content);
        var contentHash = ContextHash.sha256(contentJson);
        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), type, metadata, parentRef,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var rootArtifactId = parent.rootArtifactId();
        repository.lockVersion(rootArtifactId, type);
        lockDownstream(rootArtifactId, type);
        // 结果落库前再次核对精确父链，避免上游 Head 与验证/发布并发切换。
        parent = requireEffectiveApproved(parentRef);
        validateParentType(type, parent);
        validateScope(metadata, parent);
        if (type == ArtifactType.RELEASE) requirePassedValidation(parent);
        existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Mutation(idempotent(existing.get(), type, metadata, parentRef,
                    supersedesRef, expectedHead, contentHash), null);
        }
        var previousHead = expectHead(rootArtifactId, type, expectedHead);
        var supersedes = validateSupersedes(type, rootArtifactId, metadata, supersedesRef);
        if (!Objects.equals(
                previousHead == null ? null : previousHead.artifactId(),
                supersedes == null ? null : supersedes.artifactId())) {
            throw new ArtifactConflictException(type + " supersedes 必须与 expectedHead 精确一致");
        }
        var artifactId = "art-" + UUID.randomUUID();
        var now = Instant.now();
        var version = repository.nextVersion(rootArtifactId, type);
        insert(artifactId, type, rootArtifactId, metadata, version, ArtifactStatus.APPROVED,
                parent.artifactId(), supersedes == null ? null : supersedes.artifactId(),
                previousHead == null ? null : previousHead.artifactId(), contentJson, contentHash,
                idempotencyKey, now, metadata.actorId(), now, text(note));
        var created = require(artifactId);
        updateHead(rootArtifactId, type, previousHead, created, now);
        if (previousHead != null) supersedePreviousHead(previousHead, created, metadata.actorId(), now);
        var invalidated = invalidateDownstreamHeads(
                rootArtifactId, type, metadata.actorId(), artifactId);
        log.info("系统结果 Artifact 已物化 type={} artifactId={} version={} parent={}",
                type, artifactId, version, parent.artifactId());
        return new Mutation(require(artifactId), previousHead, invalidated);
    }

    @Transactional
    public Mutation approve(ArtifactRef artifactRef, ArtifactRef expectedHead, String actorId, String note) {
        var current = requireExact(artifactRef);
        if (current.artifactType() != ArtifactType.PLANNING
                && current.artifactType() != ArtifactType.CODING) {
            throw new IllegalArgumentException(current.artifactType() + " 不支持人工批准");
        }
        repository.lockVersion(current.rootArtifactId(), current.artifactType());
        lockDownstream(current.rootArtifactId(), current.artifactType());
        current = requireExact(artifactRef);
        if (current.status() != ArtifactStatus.PROPOSED) {
            throw new ArtifactConflictException("只有当前 PROPOSED Artifact 可以批准");
        }
        requireProposalHead(current, expectedHead);
        var previousHead = expectHead(current.rootArtifactId(), current.artifactType(), expectedHead);
        requireEffectiveApproved(ArtifactRef.from(require(current.parentArtifactId())));
        var now = Instant.now();
        if (repository.transitionStatus(current.artifactId(), ArtifactStatus.PROPOSED, ArtifactStatus.APPROVED,
                actorId, now, text(note)) != 1) {
            throw new ArtifactConflictException("Artifact 已被其他审核动作处理");
        }
        var approved = require(current.artifactId());
        updateHead(current.rootArtifactId(), current.artifactType(), previousHead, approved, now);
        if (previousHead != null && !previousHead.artifactId().equals(approved.artifactId())) {
            supersedePreviousHead(previousHead, approved, actorId, now);
        }
        var invalidated = invalidateDownstreamHeads(
                current.rootArtifactId(), current.artifactType(), actorId, approved.artifactId());
        log.info("Artifact 已批准并更新 Head artifactId={} type={} version={}",
                approved.artifactId(), approved.artifactType(), approved.version());
        return new Mutation(require(approved.artifactId()), previousHead, invalidated);
    }

    @Transactional
    public Mutation reject(ArtifactRef artifactRef, ArtifactRef expectedHead, String actorId, String note) {
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Artifact 打回意见不能为空");
        var current = requireExact(artifactRef);
        repository.lockVersion(current.rootArtifactId(), current.artifactType());
        current = requireExact(artifactRef);
        if (current.status() != ArtifactStatus.PROPOSED) {
            throw new ArtifactConflictException("只有当前 PROPOSED Artifact 可以打回");
        }
        requireProposalHead(current, expectedHead);
        expectHead(current.rootArtifactId(), current.artifactType(), expectedHead);
        if (repository.transitionStatus(current.artifactId(), ArtifactStatus.PROPOSED, ArtifactStatus.REJECTED,
                actorId, Instant.now(), note.trim()) != 1) {
            throw new ArtifactConflictException("Artifact 已被其他审核动作处理");
        }
        log.info("Artifact 已打回 artifactId={} type={} version={}",
                current.artifactId(), current.artifactType(), current.version());
        return new Mutation(require(current.artifactId()), expectedHead == null ? null : requireExact(expectedHead));
    }

    @Transactional
    public Mutation supersede(ArtifactRef artifactRef, ArtifactRef expectedHead, String actorId, String note) {
        var current = requireExact(artifactRef);
        repository.lockVersion(current.rootArtifactId(), current.artifactType());
        current = requireExact(artifactRef);
        if (current.status() == ArtifactStatus.PROPOSED) {
            requireProposalHead(current, expectedHead);
        }
        var head = expectHead(current.rootArtifactId(), current.artifactType(), expectedHead);
        if (current.status() != ArtifactStatus.PROPOSED && current.status() != ArtifactStatus.APPROVED) {
            throw new ArtifactConflictException("只有 PROPOSED 或 APPROVED Artifact 可以失效");
        }
        if (current.status() == ArtifactStatus.APPROVED) {
            if (head == null || !head.artifactId().equals(current.artifactId())) {
                throw new ArtifactConflictException("只能失效当前 Approved Head");
            }
            if (repository.clearHead(current.rootArtifactId(), current.artifactType(), current.artifactId()) != 1) {
                throw new ArtifactConflictException("Artifact Head 已变化");
            }
        }
        if (repository.transitionStatus(current.artifactId(), current.status(), ArtifactStatus.SUPERSEDED,
                actorId, Instant.now(), text(note)) != 1) {
            throw new ArtifactConflictException("Artifact 已被其他状态动作处理");
        }
        log.info("Artifact 已显式失效 artifactId={} type={} version={}",
                current.artifactId(), current.artifactType(), current.version());
        return new Mutation(require(current.artifactId()), head);
    }

    @Transactional
    public Activation activateVersion(ArtifactRef targetRef, Map<ArtifactType, ArtifactRef> expectedHeads,
                                      String actorId) {
        var initial = requireExact(targetRef);
        if (initial.artifactType() == ArtifactType.VALIDATION
                || initial.artifactType() == ArtifactType.RELEASE) {
            throw new ArtifactConflictException("Validation/Release 结果不能直接切换 Head，请走显式返工流程");
        }
        var rootArtifactId = initial.rootArtifactId();
        // 固定加锁顺序，保证整条有效路线一次完成且不会与并发审批互相覆盖。
        for (var type : ArtifactType.values()) {
            repository.lockVersion(rootArtifactId, type);
        }
        var target = requireExact(targetRef);
        var expected = expectedHeads == null ? Map.<ArtifactType, ArtifactRef>of() : Map.copyOf(expectedHeads);
        if (!effectiveHeads(rootArtifactId).equals(expected)) {
            throw new ArtifactConflictException("Artifact 有效版本已变化，请刷新后重试");
        }

        var route = selectedRoute(target);
        var currentHeads = new EnumMap<ArtifactType, Artifact>(ArtifactType.class);
        for (var head : repository.findHeads(rootArtifactId)) {
            currentHeads.put(head.artifactType(), head);
        }
        preserveCompatibleDownstream(route, currentHeads, target.artifactType());

        var changes = new ArrayList<StatusChange>();
        var routeIds = new HashSet<String>();
        for (var type : ArtifactType.values()) {
            var selected = route.get(type);
            if (selected == null) continue;
            routeIds.add(selected.artifactId());
            if (selected.status() != ArtifactStatus.APPROVED) {
                changeStatus(selected, ArtifactStatus.APPROVED, actorId,
                        "已切换为当前执行版本", changes);
                route.put(type, require(selected.artifactId()));
            }
        }

        var now = Instant.now();
        for (var type : ArtifactType.values()) {
            var previous = currentHeads.get(type);
            var selected = route.get(type);
            if (Objects.equals(
                    previous == null ? null : previous.artifactId(),
                    selected == null ? null : selected.artifactId())) {
                continue;
            }
            if (selected == null) {
                if (previous != null
                        && repository.clearHead(rootArtifactId, type, previous.artifactId()) != 1) {
                    throw new ArtifactConflictException("Artifact Head 已变化");
                }
            } else if (repository.compareAndSetHead(
                    rootArtifactId, type,
                    previous == null ? null : previous.artifactId(), selected, now) != 1) {
                throw new ArtifactConflictException("Artifact Head CAS 失败");
            }
            if (previous != null && previous.status() == ArtifactStatus.APPROVED) {
                changeStatus(previous, ArtifactStatus.SUPERSEDED, actorId,
                        "已切换到 " + type + " v" + (selected == null ? "-" : selected.version()), changes);
            }
        }

        // 待审核版本不再允许沿旧路线被批准，保留记录并明确标记为历史版本。
        for (var artifact : repository.findByRoot(rootArtifactId)) {
            if (artifact.status() == ArtifactStatus.PROPOSED && !routeIds.contains(artifact.artifactId())) {
                changeStatus(artifact, ArtifactStatus.SUPERSEDED, actorId,
                        "当前执行路线已切换", changes);
            }
        }
        var selected = require(target.artifactId());
        var effective = effectiveHeads(rootArtifactId);
        log.info("Artifact 有效版本已切换 root={} selectedType={} selectedVersion={} route={}",
                rootArtifactId, selected.artifactType(), selected.version(),
                effective.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().name(), entry -> entry.getValue().version())));
        return new Activation(selected, effective, List.copyOf(changes));
    }

    public Artifact require(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("Artifact 引用不能为空");
        }
        return repository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在: " + artifactId));
    }

    public Artifact requireExact(ArtifactRef reference) {
        if (reference == null) throw new IllegalArgumentException("缺少精确 ArtifactRef");
        var artifact = repository.findById(reference.artifactId())
                .orElseThrow(() -> new ArtifactConflictException("ArtifactRef 指向的 Artifact 不存在"));
        if (artifact.artifactType() != reference.artifactType()
                || artifact.version() != reference.version()
                || !Objects.equals(artifact.contentHash(), reference.contentHash())
                || !Objects.equals(artifact.rootArtifactId(), reference.rootArtifactId())
                || !Objects.equals(artifact.parentArtifactId(), reference.parentArtifactId())
                || !Objects.equals(artifact.supersedesArtifactId(), reference.supersedesArtifactId())
                || artifact.status() != reference.status()) {
            throw new ArtifactConflictException("ArtifactRef 已过期或与当前记录不一致");
        }
        return artifact;
    }

    public Artifact requireEffectiveApproved(ArtifactRef reference) {
        var artifact = requireExact(reference);
        if (!isEffectiveApproved(artifact, new HashSet<>())) {
            throw new ArtifactConflictException("Artifact 不是当前有效 Approved Head，或其父链已失效");
        }
        return artifact;
    }

    /** 发布门禁同时核对 Validation 内容结果与完整有效父链。 */
    public Artifact requirePassedValidation(ArtifactRef reference) {
        var artifact = requireEffectiveApproved(reference);
        requirePassedValidation(artifact);
        return artifact;
    }

    /** 待审核产物必须仍然挂在完整有效的 Approved 父链上，才能进入下一阶段。 */
    public Artifact requireEligibleProposal(ArtifactRef reference) {
        var artifact = requireExact(reference);
        if (artifact.status() != ArtifactStatus.PROPOSED || artifact.parentArtifactId() == null
                || !isEffectiveApproved(require(artifact.parentArtifactId()), new HashSet<>())) {
            throw new ArtifactConflictException("Artifact Proposal 的 Approved 父链已失效");
        }
        return artifact;
    }

    public ArtifactRef headRef(String rootArtifactId, ArtifactType type) {
        return repository.findHead(rootArtifactId, type).map(ArtifactRef::from).orElse(null);
    }

    /** Context 只按精确 Root 读取有效 Head，不通过 WorkItem 反查当前链。 */
    public Map<ArtifactType, ArtifactRef> effectiveHeads(String rootArtifactId) {
        var heads = new EnumMap<ArtifactType, ArtifactRef>(ArtifactType.class);
        for (var head : repository.findHeads(rootArtifactId)) {
            if (isEffectiveApproved(head, new HashSet<>())) {
                heads.put(head.artifactType(), ArtifactRef.from(head));
            }
        }
        return Map.copyOf(heads);
    }

    /** 只允许 PRD 确认幂等恢复使用，不按 latest 选择写入目标。 */
    public Artifact requireCurrentProduct(String systemId, String prdId) {
        var rootArtifactId = repository.findRoot(systemId, prdId)
                .orElseThrow(() -> new IllegalStateException("缺少当前 ProductArtifact Root"));
        return repository.findHead(rootArtifactId, ArtifactType.PRODUCT)
                .orElseThrow(() -> new IllegalStateException("缺少当前 ProductArtifact Head"));
    }

    /** latest 仅供只读 UI。 */
    public Artifact findLatestForDisplay(String workItemId, ArtifactType type) {
        return repository.findLatestForDisplay(workItemId, type).orElse(null);
    }

    public List<Artifact> findByPrd(String prdId, ArtifactType type) {
        return repository.findByPrd(prdId, type);
    }

    public List<Artifact> findChildren(String artifactId) {
        return repository.findChildren(artifactId);
    }

    public List<Artifact> findArtifactChain(String workItemId) {
        return repository.findByWorkItem(workItemId);
    }

    public List<Artifact> findAncestors(String artifactId) {
        var result = new ArrayList<Artifact>();
        var visited = new HashSet<String>();
        var current = require(artifactId);
        while (current != null && visited.add(current.artifactId())) {
            result.add(current);
            current = current.parentArtifactId() == null ? null : require(current.parentArtifactId());
        }
        return List.copyOf(result);
    }

    public List<Artifact> findVersionHistory(String artifactId) {
        var artifact = require(artifactId);
        return repository.findByRootAndType(artifact.rootArtifactId(), artifact.artifactType());
    }

    public ArtifactGraph graph(String workItemId) {
        var nodes = repository.findByWorkItem(workItemId);
        if (nodes.isEmpty()) return new ArtifactGraph("", List.of(), List.of(), Map.of());
        var root = nodes.getFirst().rootArtifactId();
        var edges = new ArrayList<ArtifactGraph.Edge>();
        for (var node : nodes) {
            if (node.parentArtifactId() != null) {
                edges.add(new ArtifactGraph.Edge(
                        node.parentArtifactId(), node.artifactId(), ArtifactGraph.EdgeType.DERIVED_FROM));
            }
            if (node.supersedesArtifactId() != null) {
                edges.add(new ArtifactGraph.Edge(
                        node.supersedesArtifactId(), node.artifactId(), ArtifactGraph.EdgeType.SUPERSEDES));
            }
        }
        return new ArtifactGraph(root, List.copyOf(nodes), List.copyOf(edges), effectiveHeads(root));
    }

    public List<ArtifactTransition> transitions(String artifactId) {
        require(artifactId);
        return repository.findTransitions(artifactId);
    }

    public List<ArtifactEvidence> evidence(String artifactId) {
        require(artifactId);
        return repository.findEvidenceByArtifact(artifactId);
    }

    public String calculateContentHash(ArtifactContent content) {
        return ContextHash.sha256(canonicalJson(content));
    }

    public String calculateHash(Object value) {
        return ContextHash.sha256(canonicalJson(value));
    }

    private boolean isEffectiveApproved(Artifact artifact, Set<String> visited) {
        if (!visited.add(artifact.artifactId()) || artifact.status() != ArtifactStatus.APPROVED) return false;
        var head = repository.findHead(artifact.rootArtifactId(), artifact.artifactType()).orElse(null);
        if (head == null || !head.artifactId().equals(artifact.artifactId())) return false;
        return artifact.parentArtifactId() == null
                || isEffectiveApproved(require(artifact.parentArtifactId()), visited);
    }

    private EnumMap<ArtifactType, Artifact> selectedRoute(Artifact target) {
        var route = new EnumMap<ArtifactType, Artifact>(ArtifactType.class);
        var visited = new HashSet<String>();
        Artifact current = target;
        while (current != null && visited.add(current.artifactId())) {
            if (!current.rootArtifactId().equals(target.rootArtifactId())
                    || !current.systemId().equals(target.systemId())
                    || !current.prdId().equals(target.prdId())
                    || !current.workItemId().equals(target.workItemId())
                    || !current.caseId().equals(target.caseId())) {
                throw new ArtifactConflictException("Artifact 父版本不属于同一工作项");
            }
            route.put(current.artifactType(), current);
            if (current.artifactType() == ArtifactType.PRODUCT) {
                if (current.parentArtifactId() != null) {
                    throw new ArtifactConflictException("ProductArtifact 不能包含父版本");
                }
                current = null;
                continue;
            }
            if (current.parentArtifactId() == null) {
                throw new ArtifactConflictException("Artifact 缺少父版本关系");
            }
            var parent = require(current.parentArtifactId());
            validateParentType(current.artifactType(), parent);
            current = parent;
        }
        if (current != null || !route.containsKey(ArtifactType.PRODUCT)) {
            throw new ArtifactConflictException("Artifact 父版本关系存在循环或不完整");
        }
        return route;
    }

    private void preserveCompatibleDownstream(EnumMap<ArtifactType, Artifact> route,
                                              Map<ArtifactType, Artifact> currentHeads,
                                              ArtifactType selectedType) {
        for (var type : ArtifactType.values()) {
            if (type.ordinal() <= selectedType.ordinal()) continue;
            var parentType = type.parentType();
            var parent = route.get(parentType);
            var head = currentHeads.get(type);
            if (parent != null && head != null && head.status() == ArtifactStatus.APPROVED
                    && Objects.equals(head.parentArtifactId(), parent.artifactId())) {
                route.put(type, head);
            }
        }
    }

    private void changeStatus(Artifact artifact, ArtifactStatus to, String actorId,
                              String note, List<StatusChange> changes) {
        var now = Instant.now();
        if (repository.transitionStatus(
                artifact.artifactId(), artifact.status(), to, actorId, now, note) != 1) {
            throw new ArtifactConflictException("Artifact 状态已变化");
        }
        changes.add(new StatusChange(require(artifact.artifactId()), artifact.status(), to, note));
    }

    private Artifact expectHead(String rootArtifactId, ArtifactType type, ArtifactRef expected) {
        var actual = repository.findHead(rootArtifactId, type).orElse(null);
        if (expected == null) {
            if (actual != null) throw new ArtifactConflictException("Artifact Head 已变化，期望为空");
            return null;
        }
        var referenced = requireExact(expected);
        if (referenced.status() != ArtifactStatus.APPROVED
                || actual == null || !actual.artifactId().equals(referenced.artifactId())) {
            throw new ArtifactConflictException("Artifact Head 已变化");
        }
        return actual;
    }

    private void requireProposalHead(Artifact proposal, ArtifactRef expectedHead) {
        if (!Objects.equals(
                proposal.expectedHeadArtifactId(),
                expectedHead == null ? null : expectedHead.artifactId())) {
            throw new ArtifactConflictException("Proposal 创建时绑定的 expectedHead 已变化");
        }
    }

    private void updateHead(String rootArtifactId, ArtifactType type, Artifact previous,
                            Artifact next, Instant now) {
        var expectedId = previous == null ? null : previous.artifactId();
        if (repository.compareAndSetHead(rootArtifactId, type, expectedId, next, now) != 1) {
            throw new ArtifactConflictException("Artifact Head CAS 失败");
        }
    }

    private void supersedePreviousHead(Artifact previous, Artifact next, String actorId, Instant now) {
        if (repository.transitionStatus(previous.artifactId(), ArtifactStatus.APPROVED,
                ArtifactStatus.SUPERSEDED, actorId, now, "已由 " + next.artifactId() + " 替代") != 1) {
            throw new ArtifactConflictException("旧 Artifact Head 状态已变化");
        }
    }

    private void lockDownstream(String rootArtifactId, ArtifactType sourceType) {
        for (var type : ArtifactType.values()) {
            if (type.ordinal() > sourceType.ordinal()) repository.lockVersion(rootArtifactId, type);
        }
    }

    /** 上游 Head 变化必须在同一事务显式失效下游，不能只依赖查询时过滤父链。 */
    private List<StatusChange> invalidateDownstreamHeads(
            String rootArtifactId, ArtifactType sourceType, String actorId, String sourceArtifactId) {
        var changes = new ArrayList<StatusChange>();
        var now = Instant.now();
        for (var type : ArtifactType.values()) {
            if (type.ordinal() <= sourceType.ordinal()) continue;
            var head = repository.findHead(rootArtifactId, type).orElse(null);
            if (head == null) continue;
            if (repository.clearHead(rootArtifactId, type, head.artifactId()) != 1) {
                throw new ArtifactConflictException("下游 Artifact Head 已变化");
            }
            var note = "上游 " + sourceType + " Head 已切换到 " + sourceArtifactId;
            if (repository.transitionStatus(
                    head.artifactId(), ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED,
                    actorId, now, note) != 1) {
                throw new ArtifactConflictException("下游 Artifact 状态已变化");
            }
            changes.add(new StatusChange(
                    require(head.artifactId()), ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED, note));
        }
        return List.copyOf(changes);
    }

    private void validateParentType(ArtifactType type, Artifact parent) {
        var expected = type.parentType();
        if (expected == null) {
            throw new IllegalArgumentException("ProductArtifact 不能包含父节点");
        }
        if (parent.artifactType() != expected) {
            throw new IllegalArgumentException(type + " 的父节点必须是 " + expected + " Artifact");
        }
    }

    private void requirePassedValidation(Artifact artifact) {
        if (artifact.artifactType() != ArtifactType.VALIDATION) {
            throw new ArtifactConflictException("Release 的父节点必须是 ValidationArtifact");
        }
        final ValidationArtifactContent content;
        try {
            content = objectMapper.convertValue(artifact.content(), ValidationArtifactContent.class);
        } catch (IllegalArgumentException error) {
            throw new ArtifactConflictException("ValidationArtifact 内容无法用于发布门禁");
        }
        // SKIP + SKIPPED 是系统配置明确选择的发布豁免，不表示验证通过。
        var skipExemption = content.mode() == ValidationArtifactContent.Mode.SKIP
                && content.result() == ValidationArtifactContent.Result.SKIPPED;
        if (content.result() != ValidationArtifactContent.Result.PASSED && !skipExemption) {
            throw new ArtifactConflictException("只有 PASSED 或明确 SKIP 豁免的 ValidationArtifact 可以发布");
        }
        var coding = require(artifact.parentArtifactId());
        var codingHead = repository.findHead(artifact.rootArtifactId(), ArtifactType.CODING).orElse(null);
        if (codingHead == null || !codingHead.artifactId().equals(coding.artifactId())) {
            throw new ArtifactConflictException("ValidationArtifact 不属于当前 Coding Head");
        }
    }

    private Artifact validateSupersedes(ArtifactType type, String rootArtifactId, Metadata metadata,
                                        ArtifactRef supersedesRef) {
        if (supersedesRef == null) return null;
        var previous = requireExact(supersedesRef);
        if (previous.artifactType() != type || !previous.rootArtifactId().equals(rootArtifactId)
                || !sameScope(previous, metadata)) {
            throw new IllegalArgumentException("supersedes 必须是相同类型、相同 Root 的精确 ArtifactRef");
        }
        return previous;
    }

    private void validateScope(Metadata metadata, Artifact artifact) {
        if (!sameScope(artifact, metadata)) {
            throw new IllegalArgumentException("Artifact 不属于当前工作项");
        }
    }

    private boolean sameScope(Artifact artifact, Metadata metadata) {
        return Objects.equals(artifact.systemId(), metadata.systemId())
                && Objects.equals(artifact.prdId(), metadata.prdId())
                && Objects.equals(artifact.workItemId(), metadata.workItemId())
                && Objects.equals(artifact.caseId(), metadata.caseId());
    }

    private Artifact idempotent(Artifact existing, ArtifactType type, Metadata metadata,
                                ArtifactRef parent, ArtifactRef supersedes,
                                ArtifactRef expectedHead, String contentHash) {
        if (existing.artifactType() != type || !sameScope(existing, metadata)
                || !Objects.equals(existing.parentArtifactId(), parent == null ? null : parent.artifactId())
                || !Objects.equals(existing.supersedesArtifactId(),
                        supersedes == null ? null : supersedes.artifactId())
                || !Objects.equals(existing.expectedHeadArtifactId(),
                        expectedHead == null ? null : expectedHead.artifactId())
                || !existing.contentHash().equals(contentHash)) {
            throw new ArtifactConflictException(
                    "Artifact 幂等键已被不同 parent、supersedes、expectedHead、scope 或 Content 使用");
        }
        return existing;
    }

    private String contentJson(ArtifactType type, ArtifactContent content) {
        validateContentType(type, content);
        var contentJson = canonicalJson(content);
        validateSafeContent(contentJson);
        return contentJson;
    }

    private void validateContentType(ArtifactType type, ArtifactContent content) {
        var valid = switch (type) {
            case PRODUCT -> content instanceof ProductArtifactContent;
            case PLANNING -> content instanceof PlanningArtifactContent;
            case CODING -> content instanceof CodingArtifactContent;
            case VALIDATION -> content instanceof ValidationArtifactContent;
            case RELEASE -> content instanceof ReleaseArtifactContent;
        };
        if (!valid) throw new IllegalArgumentException(type + " Content 契约不匹配");
        if (content instanceof CodingArtifactContent coding
                && coding.repoChanges().stream().noneMatch(change ->
                change.diffPatch() != null && change.diffPatch().contains("diff --git"))) {
            throw new IllegalArgumentException("CodingArtifact 必须包含正式 Git Diff");
        }
        if (content instanceof ValidationArtifactContent validation) {
            if (validation.validationRunId() == null || validation.validationRunId().isBlank()
                    || validation.mode() == null || validation.result() == null
                    || validation.codingContentHash() == null || validation.codingContentHash().isBlank()
                    || validation.completedAt() == null) {
                throw new IllegalArgumentException("ValidationArtifact 缺少验证结果字段");
            }
        }
        if (content instanceof ReleaseArtifactContent release) {
            if (release.releaseId() == null || release.releaseId().isBlank()
                    || release.releaseMode() == null || release.releaseMode().isBlank()
                    || release.targetKey() == null || release.targetKey().isBlank()
                    || release.codingArtifact() == null || release.validationArtifact() == null
                    || release.completedAt() == null) {
                throw new IllegalArgumentException("ReleaseArtifact 缺少发布清单字段");
            }
        }
    }

    private void validateSafeContent(String contentJson) {
        try {
            scanKeys(objectMapper.readTree(contentJson));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Artifact Content 不是合法 JSON", error);
        }
    }

    private void scanKeys(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                var key = entry.getKey().replace("-", "").toLowerCase(Locale.ROOT);
                if (FORBIDDEN_CONTENT_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Artifact Content 禁止包含字段: " + entry.getKey());
                }
                scanKeys(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::scanKeys);
        } else if (node.isTextual()) {
            var value = node.asText().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_CONTENT_VALUES.stream().anyMatch(value::contains)) {
                throw new IllegalArgumentException("Artifact Content 禁止包含密钥或 Token");
            }
        }
    }

    private void insert(String artifactId, ArtifactType type, String rootArtifactId, Metadata metadata,
                        int version, ArtifactStatus status, String parentArtifactId, String supersedesArtifactId,
                        String expectedHeadArtifactId, String contentJson, String contentHash,
                        String idempotencyKey, Instant createdAt,
                        String reviewedBy, Instant reviewedAt, String reviewNote) {
        repository.insert(new ArtifactRepository.InsertArtifact(
                artifactId, type, rootArtifactId, metadata.systemId(), metadata.prdId(),
                metadata.workItemId(), metadata.caseId(), version, status, parentArtifactId,
                supersedesArtifactId, expectedHeadArtifactId, contentJson, contentHash,
                idempotencyKey, metadata.actorId(),
                createdAt, reviewedBy, reviewedAt, reviewNote));
    }

    private String canonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Artifact 数据不能序列化", error);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record Mutation(Artifact artifact, Artifact previousHead, List<StatusChange> downstreamChanges) {
        public Mutation(Artifact artifact, Artifact previousHead) {
            this(artifact, previousHead, List.of());
        }
    }

    public record Activation(
            Artifact selectedArtifact,
            Map<ArtifactType, ArtifactRef> effectiveHeads,
            List<StatusChange> statusChanges) {
    }

    public record StatusChange(
            Artifact artifact,
            ArtifactStatus fromStatus,
            ArtifactStatus toStatus,
            String note) {
    }

    public record Metadata(String systemId, String prdId, String workItemId, String caseId, String actorId) {
    }
}
