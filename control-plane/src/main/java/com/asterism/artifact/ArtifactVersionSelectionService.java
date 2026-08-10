package com.asterism.artifact;

import com.asterism.common.ApiException;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArtifactVersionSelectionService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactVersionSelectionService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "rejected");

    private final ArtifactService artifacts;
    private final ArtifactTransitionService transitions;
    private final DomainEventService events;
    private final TemporalCasePort temporal;
    private final WorkItemProjectionRepository workItems;
    private final ObjectMapper eventMapper;
    private final ObjectMapper signalMapper;
    private final TransactionOperations transactions;

    public ArtifactVersionSelectionService(ArtifactService artifacts,
                                           ArtifactTransitionService transitions,
                                           DomainEventService events,
                                           TemporalCasePort temporal,
                                           WorkItemProjectionRepository workItems,
                                           ObjectMapper objectMapper,
                                           TransactionOperations transactions) {
        this.artifacts = artifacts;
        this.transitions = transitions;
        this.events = events;
        this.temporal = temporal;
        this.workItems = workItems;
        this.eventMapper = objectMapper;
        this.signalMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.transactions = transactions;
    }

    public SelectionResponse select(WorkItemProjection item, SelectionRequest request, String actorId) {
        var result = transactions.execute(status -> selectVersion(lock(item), request, actorId));
        if (result == null) throw new IllegalStateException("Artifact 版本切换失败");
        // 版本选择只更新有效路线；是否继续执行由用户单独确认。
        log.info("Artifact 当前版本已切换 workItem={} type={} version={}",
                item.workItemId(), request.artifact().artifactType(), request.artifact().version());
        return new SelectionResponse(item.displayWorkItemId(), "", "selected", result.effectiveHeads());
    }

    public SelectionResponse continueExecution(
            WorkItemProjection item, SelectionRequest request, String actorId) {
        var prepared = transactions.execute(status -> prepareExecution(lock(item), request, actorId));
        if (prepared == null) throw new IllegalStateException("Artifact 继续执行准备失败");
        if (!prepared.dispatch()) {
            return new SelectionResponse(
                    item.displayWorkItemId(), prepared.signalId(), "submitted", prepared.effectiveHeads());
        }
        try {
            temporal.signalCase(new TemporalCasePort.SignalCaseCommand(
                    item.caseId(), "artifact_version_selected", prepared.signalId(), prepared.context()));
        } catch (RuntimeException error) {
            events.append(new DomainEventService.AppendEvent(
                    DomainEventType.TemporalSignalFailed,
                    item.systemId(), item.caseId(), item.prdId(), item.workItemId(),
                    actorId, "control-plane",
                    Map.of(
                            "signalName", "artifact_version_selected",
                            "signalId", prepared.signalId(),
                            "reason", String.valueOf(error.getMessage())),
                    item.caseId(), null, "signal-failed:" + prepared.submissionKey()));
            log.error("基于 Artifact 继续执行失败 workItem={} signal={}",
                    item.workItemId(), prepared.signalId(), error);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORAL_SIGNAL_FAILED",
                    "当前版本已保留，工作流暂时不可用，请重试继续开发");
        }
        log.info("已基于当前 Artifact 路线继续执行 workItem={} signal={}",
                item.workItemId(), prepared.signalId());
        return new SelectionResponse(
                item.displayWorkItemId(), prepared.signalId(), "submitted", prepared.effectiveHeads());
    }

    private ArtifactTransitionService.VersionSelectionResult selectVersion(
            WorkItemProjection item, SelectionRequest request, String actorId) {
        validateRequest(item, request);
        var transitionId = "SelectArtifactVersion:" + item.workItemId() + ":" + request.requestId().trim();
        var idempotencyKey = "artifact-version-selection:" + item.workItemId() + ":" + request.requestId().trim();
        // 已成功提交的同一命令直接交给 Transition 层幂等恢复，避免状态推进后把合法重试误判为过期请求。
        if (!events.exists(idempotencyKey)) {
            var target = exactTarget(item, request.artifact());
            var availability = actionAvailability(item, target, true, runtimeFacts(item));
            if (!availability.canSelect()) {
                throw new ApiException(HttpStatus.CONFLICT, availability.selectErrorCode(),
                        availability.selectDisabledReason());
            }
        }
        return transitions.selectVersion(
                new ArtifactTransitionService.EventMetadata(
                        DomainEventType.ArtifactVersionSelected,
                        item.systemId(), item.caseId(), item.prdId(), item.workItemId(),
                        actorId, "control-plane", item.caseId(), transitionId,
                        idempotencyKey),
                request.artifact(), request.expectedHeads(), transitionId);
    }

    private PreparedSelection prepareExecution(
            WorkItemProjection item, SelectionRequest request, String actorId) {
        validateRequest(item, request);
        if (request.artifact().artifactType() != ArtifactType.PLANNING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ARTIFACT_CONTINUE_NOT_AVAILABLE",
                    "请选择当前有效的执行计划");
        }
        var requestId = request.requestId().trim();
        var signalId = "artifact-version-continue-" + requestId;
        var baseSubmissionKey = "artifact-version-continue:" + item.workItemId() + ":" + requestId;
        var replay = continueReplay(item, request, baseSubmissionKey, signalId);
        if (replay != null) {
            return new PreparedSelection(signalId, baseSubmissionKey, Map.of(), replay, false);
        }
        var target = exactTarget(item, request.artifact());
        var availability = actionAvailability(item, target, true, runtimeFacts(item));
        if (!availability.canContinue()) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_CONTINUE_NOT_AVAILABLE",
                    availability.continueDisabledReason());
        }
        var expected = request.expectedHeads() == null
                ? Map.<ArtifactType, ArtifactRef>of() : Map.copyOf(request.expectedHeads());
        var current = artifacts.effectiveHeads(request.artifact().rootArtifactId());
        if (!current.equals(expected)
                || !request.artifact().equals(current.get(ArtifactType.PLANNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前执行版本已变化，请刷新后重试");
        }
        final Artifact planning;
        try {
            planning = artifacts.requireEffectiveApproved(request.artifact());
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前执行版本已变化，请刷新后重试");
        }
        if (!planning.workItemId().equals(item.workItemId())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "执行计划不属于当前工作项");
        }
        var product = current.get(ArtifactType.PRODUCT);
        var manifestId = artifacts.require(product.artifactId())
                .content().path("requirementManifestId").asText();
        var context = signalContext(
                request.artifact().artifactType(), request.artifact(), current, manifestId);
        var attempt = events.countSignalFailures(item.workItemId(), signalId) + 1;
        var submissionKey = attempt == 1
                ? baseSubmissionKey : baseSubmissionKey + ":retry:" + attempt;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.TemporalSignalSubmitted,
                item.systemId(), item.caseId(), item.prdId(), item.workItemId(),
                actorId, "control-plane",
                Map.of(
                        "signalName", "artifact_version_selected",
                        "signalId", signalId,
                        "requestId", requestId,
                        "selectedType", request.artifact().artifactType().name(),
                        "selectedVersion", request.artifact().version(),
                        "artifactRef", request.artifact(),
                        "expectedHeads", expected,
                        "attempt", attempt),
                item.caseId(), null, submissionKey));
        return new PreparedSelection(
                signalId, submissionKey, context, current, true);
    }

    private void validateRequest(WorkItemProjection item, SelectionRequest request) {
        if (request == null || request.artifact() == null) {
            throw new IllegalArgumentException("请选择要继续使用的 Artifact 版本");
        }
        var requestId = request.requestId() == null ? "" : request.requestId().trim();
        if (!requestId.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "requestId 格式不正确");
        }
    }

    private Map<ArtifactType, ArtifactRef> continueReplay(
            WorkItemProjection item, SelectionRequest request, String submissionKey, String signalId) {
        if (!events.exists(submissionKey)
                || events.hasUnrecoveredSignalFailure(item.workItemId(), signalId)) {
            return null;
        }
        var submitted = events.findByWorkItemId(item.workItemId()).stream()
                .filter(event -> submissionKey.equals(event.idempotencyKey()))
                .findFirst()
                .orElseThrow(() -> new ArtifactConflictException("继续执行幂等事件不存在"));
        var payload = eventPayload(submitted.payloadJson());
        var existingArtifact = payload.get("artifactRef");
        if (existingArtifact == null) {
            if (!request.artifact().artifactType().name().equals(payload.get("selectedType"))
                    || request.artifact().version() != number(payload.get("selectedVersion"))) {
                throw new ArtifactConflictException("继续执行 requestId 已被不同 Artifact 版本使用");
            }
        } else {
            var reference = eventMapper.convertValue(existingArtifact, ArtifactRef.class);
            var expected = eventMapper.convertValue(
                    payload.getOrDefault("expectedHeads", Map.of()),
                    new TypeReference<Map<ArtifactType, ArtifactRef>>() {});
            var requestedHeads = request.expectedHeads() == null
                    ? Map.<ArtifactType, ArtifactRef>of() : Map.copyOf(request.expectedHeads());
            if (!reference.equals(request.artifact()) || !expected.equals(requestedHeads)) {
                throw new ArtifactConflictException("继续执行 requestId 已被不同 Artifact 路线使用");
            }
        }
        return artifacts.effectiveHeads(request.artifact().rootArtifactId());
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    /** Artifact Graph 的按钮能力完全由后端按精确版本计算，前端只负责展示。 */
    public Map<String, VersionActionAvailability> versionActions(
            WorkItemProjection item, List<Artifact> chain, boolean canControl) {
        var facts = runtimeFacts(item, chain);
        var result = new LinkedHashMap<String, VersionActionAvailability>();
        for (var artifact : chain) {
            result.put(artifact.artifactId(), actionAvailability(item, artifact, canControl, facts).publicView());
        }
        return Map.copyOf(result);
    }

    private VersionActionPolicy actionAvailability(
            WorkItemProjection item, Artifact target, boolean canControl, RuntimeFacts facts) {
        if (!canControl) {
            return denied("FORBIDDEN", "仅工作项负责人或管理员可以切换版本",
                    "仅工作项负责人或管理员可以继续开发");
        }
        if (TERMINAL_STATUSES.contains(item.lifecycleStatus())) {
            return denied("ARTIFACT_VERSION_SWITCH_NOT_AVAILABLE", "工作项已结束，不能再切换版本",
                    "工作项已结束，不能再继续开发");
        }
        if (facts.pendingAction()) {
            return denied("ACTION_PENDING", "当前操作完成后可以切换版本",
                    "当前操作完成后可以继续开发");
        }

        var current = facts.effectiveHeads().get(target.artifactType());
        var currentTarget = current != null && current.artifactId().equals(target.artifactId());
        var selectPolicy = target.status() == ArtifactStatus.PROPOSED
                ? new Decision(false, "ARTIFACT_VERSION_SWITCH_NOT_AVAILABLE",
                "待审核产物必须使用当前审批操作")
                : currentTarget
                ? new Decision(false, "ARTIFACT_VERSION_SWITCH_NOT_AVAILABLE", "该版本已是当前有效版本")
                : switch (target.artifactType()) {
                    case PRODUCT -> productSelection(facts);
                    case PLANNING -> planningSelection(item, target, facts);
                    case CODING -> codingSelection(item, target, facts);
                    case VALIDATION, RELEASE -> rollbackRequired(
                            "验证与发布结果不能直接切换版本，请使用显式返工操作");
                };
        var continuePolicy = planningContinuation(item, target, current, facts);
        return new VersionActionPolicy(
                selectPolicy.allowed(), selectPolicy.errorCode(), selectPolicy.reason(),
                continuePolicy.allowed(), continuePolicy.reason());
    }

    private Decision productSelection(RuntimeFacts facts) {
        if (facts.hasPlanningArtifact() || facts.planningStarted()) {
            return rollbackRequired("Planning 已开始，切换 Product 需要显式回退并重建执行上下文");
        }
        // Temporal CaseInput 在工作项创建时已绑定 ProductRef，普通 Head 切换无法同步该不可变输入。
        return rollbackRequired("Product 版本切换需要显式重建执行上下文，当前不能直接切换");
    }

    private Decision planningSelection(WorkItemProjection item, Artifact target, RuntimeFacts facts) {
        if (facts.hasCodingArtifact() || facts.codingStarted()) {
            return rollbackRequired("Coding 已开始，切换 Planning 必须先显式回退并重新执行");
        }
        if (!"activated".equals(item.lifecycleStatus())) {
            return new Decision(false, "ARTIFACT_VERSION_SWITCH_NOT_AVAILABLE",
                    "当前执行阶段不能直接切换 Planning");
        }
        var product = facts.effectiveHeads().get(ArtifactType.PRODUCT);
        if (product == null || !product.artifactId().equals(target.parentArtifactId())
                || !target.rootArtifactId().equals(product.rootArtifactId())) {
            return rollbackRequired("该 Planning 版本来自旧 Product，切换会改变需求基线，请先显式回退");
        }
        return new Decision(true, "", "");
    }

    private Decision codingSelection(WorkItemProjection item, Artifact target, RuntimeFacts facts) {
        if (!"modification_completed".equals(item.lifecycleStatus())) {
            return rollbackRequired("Coding 历史版本只能在代码确认阶段切换");
        }
        var planning = facts.effectiveHeads().get(ArtifactType.PLANNING);
        var product = facts.effectiveHeads().get(ArtifactType.PRODUCT);
        if (planning == null || product == null) {
            return rollbackRequired("当前有效 Artifact 上游路线不完整，切换 Coding 需要显式回退");
        }
        // 普通 Coding 切换只能留在当前上游路线，避免 activateVersion 隐式改写 Planning Head。
        if (!planning.artifactId().equals(target.parentArtifactId())) {
            return rollbackRequired("该 Coding 版本来自旧 Planning，切换会改变上游路线，请先显式回退");
        }
        if (!product.artifactId().equals(planning.parentArtifactId())
                || !target.rootArtifactId().equals(planning.rootArtifactId())
                || !target.rootArtifactId().equals(product.rootArtifactId())) {
            return rollbackRequired("该 Coding 版本的 Product 祖先不是当前有效 Product，请先显式回退");
        }
        return new Decision(true, "", "");
    }

    private Decision planningContinuation(WorkItemProjection item, Artifact target,
                                          ArtifactRef current, RuntimeFacts facts) {
        if (target.artifactType() != ArtifactType.PLANNING) {
            return new Decision(false, "ARTIFACT_CONTINUE_NOT_AVAILABLE", "只有当前执行计划可以继续开发");
        }
        if (current == null || !current.artifactId().equals(target.artifactId())
                || target.status() != ArtifactStatus.APPROVED) {
            return new Decision(false, "ARTIFACT_CONTINUE_NOT_AVAILABLE", "请先切换到该执行计划");
        }
        if (facts.hasCodingArtifact() || facts.codingStarted()) {
            return new Decision(false, "ARTIFACT_CONTINUE_NOT_AVAILABLE",
                    "Coding 已开始，不能重复启动；如需换计划请走显式回退");
        }
        if (!"activated".equals(item.lifecycleStatus())) {
            return new Decision(false, "ARTIFACT_CONTINUE_NOT_AVAILABLE", "当前阶段不能启动新的 Coding");
        }
        return new Decision(true, "", "");
    }

    private Decision rollbackRequired(String reason) {
        return new Decision(false, "ARTIFACT_VERSION_ROLLBACK_REQUIRED", reason);
    }

    private VersionActionPolicy denied(String code, String selectReason, String continueReason) {
        return new VersionActionPolicy(false, code, selectReason, false, continueReason);
    }

    private Artifact exactTarget(WorkItemProjection item, ArtifactRef reference) {
        final Artifact target;
        try {
            target = artifacts.requireExact(reference);
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT", "产物版本已变化，请刷新后重试");
        }
        if (!target.workItemId().equals(item.workItemId())
                || !target.caseId().equals(item.caseId())
                || !target.prdId().equals(item.prdId())
                || !target.systemId().equals(item.systemId())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT", "产物版本不属于当前工作项");
        }
        return target;
    }

    private RuntimeFacts runtimeFacts(WorkItemProjection item) {
        return runtimeFacts(item, artifacts.findArtifactChain(item.workItemId()));
    }

    private RuntimeFacts runtimeFacts(WorkItemProjection item, List<Artifact> chain) {
        var eventTypes = new LinkedHashSet<String>();
        var pendingSignals = new LinkedHashSet<String>();
        for (var event : events.findByWorkItemId(item.workItemId())) {
            eventTypes.add(event.eventType());
            var payload = eventPayload(event.payloadJson());
            var signalId = String.valueOf(payload.getOrDefault("signalId", "")).trim();
            if (signalId.isBlank()) continue;
            if (Set.of("OwnerApprovalSignalSubmitted", "TemporalSignalSubmitted").contains(event.eventType())) {
                pendingSignals.add(signalId);
            } else if (Set.of("TemporalSignalFailed", "TemporalActionCompleted").contains(event.eventType())) {
                pendingSignals.remove(signalId);
            }
        }
        var root = chain.isEmpty() ? "" : chain.getFirst().rootArtifactId();
        var heads = root.isBlank() ? Map.<ArtifactType, ArtifactRef>of() : artifacts.effectiveHeads(root);
        return new RuntimeFacts(
                !pendingSignals.isEmpty(),
                chain.stream().anyMatch(value -> value.artifactType() == ArtifactType.PLANNING),
                chain.stream().anyMatch(value -> value.artifactType() == ArtifactType.CODING),
                eventTypes.contains("CodingPlanStarted"),
                eventTypes.contains("CodingAttemptStarted"),
                heads);
    }

    private Map<String, Object> eventPayload(String payloadJson) {
        try {
            return signalMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private WorkItemProjection lock(WorkItemProjection item) {
        return workItems.lockById(item.workItemId())
                .filter(value -> !value.deleted())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "STALE_WORK_ITEM",
                        "工作项状态已变化，请刷新后重试"));
    }

    private Map<String, Object> signalContext(ArtifactType selectedType, ArtifactRef selected,
                                               Map<ArtifactType, ArtifactRef> heads,
                                               String requirementManifestId) {
        var context = new LinkedHashMap<String, Object>();
        context.put("selected_type", selectedType.name());
        context.put("selected_artifact", refPayload(selected));
        context.put("requirement_manifest_id", requirementManifestId);
        addRef(context, "product_artifact", heads.get(ArtifactType.PRODUCT));
        addRef(context, "planning_artifact", heads.get(ArtifactType.PLANNING));
        addRef(context, "coding_artifact", heads.get(ArtifactType.CODING));
        return Map.copyOf(context);
    }

    private void addRef(Map<String, Object> context, String key, ArtifactRef reference) {
        if (reference != null) context.put(key, refPayload(reference));
    }

    private Map<String, Object> refPayload(ArtifactRef reference) {
        return signalMapper.convertValue(reference, new TypeReference<>() {
        });
    }

    public record SelectionRequest(
            String requestId,
            ArtifactRef artifact,
            Map<ArtifactType, ArtifactRef> expectedHeads) {
    }

    public record SelectionResponse(
            String workItemId,
            String signalId,
            String status,
            Map<ArtifactType, ArtifactRef> effectiveHeads) {
    }

    public record VersionActionAvailability(
            boolean canSelect,
            String selectDisabledReason,
            boolean canContinue,
            String continueDisabledReason) {
    }

    private record Decision(boolean allowed, String errorCode, String reason) {
    }

    private record VersionActionPolicy(
            boolean canSelect,
            String selectErrorCode,
            String selectDisabledReason,
            boolean canContinue,
            String continueDisabledReason) {
        VersionActionAvailability publicView() {
            return new VersionActionAvailability(
                    canSelect, selectDisabledReason, canContinue, continueDisabledReason);
        }
    }

    private record RuntimeFacts(
            boolean pendingAction,
            boolean hasPlanningArtifact,
            boolean hasCodingArtifact,
            boolean planningStarted,
            boolean codingStarted,
            Map<ArtifactType, ArtifactRef> effectiveHeads) {
    }

    private record PreparedSelection(
            String signalId,
            String submissionKey,
            Map<String, Object> context,
            Map<ArtifactType, ArtifactRef> effectiveHeads,
            boolean dispatch) {
    }
}
