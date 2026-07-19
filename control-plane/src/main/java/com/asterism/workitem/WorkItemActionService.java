package com.asterism.workitem;

import com.asterism.common.ApiException;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class WorkItemActionService {
    private static final Logger log = LoggerFactory.getLogger(WorkItemActionService.class);
    private static final Map<String, List<String>> ACTIONS = Map.ofEntries(
            Map.entry("waiting_owner_approval", List.of("owner_approved", "owner_rejected", "cancel_case")),
            Map.entry("activated", List.of("start_modification", "cancel_case")),
            Map.entry("worker_blocked", List.of(
                    "retry_current_phase", "rework", "rework_with_latest_config", "cancel_case")),
            Map.entry("patch_rejected", List.of("rework", "cancel_case")),
            Map.entry("validation_failed", List.of("rework", "cancel_case")),
            Map.entry("modification_completed", List.of("patch_apply_approved", "patch_apply_rejected", "cancel_case")),
            Map.entry("patch_applied", List.of("validation_passed", "validation_rejected")),
            Map.entry("validation_passed", List.of("release_approved", "cancel_case")),
            Map.entry("waiting_merge", List.of("check_merge_status", "rework", "cancel_case")));
    private static final Set<String> VALIDATION_ACTIONS = Set.of("validation_passed", "validation_rejected");
    private static final Set<String> CONFIG_REFRESH_PHASES = Set.of("coding");
    private static final Predicate<RuntimeState> ALWAYS_AVAILABLE = ignored -> true;
    private static final Map<String, Predicate<RuntimeState>> ACTION_GUARDS = Map.of(
            "retry_current_phase", RuntimeState::phaseRetrySupported,
            "rework_with_latest_config", RuntimeState::configurationRefreshSupported);

    private final WorkItemProjectionRepository workItems;
    private final TemporalCasePort temporal;
    private final DomainEventService events;
    private final SystemAccessService access;
    private final AgentConfigurationService configurations;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactions;

    public WorkItemActionService(WorkItemProjectionRepository workItems, TemporalCasePort temporal,
                                 DomainEventService events, SystemAccessService access,
                                 AgentConfigurationService configurations, ObjectMapper objectMapper,
                                 TransactionOperations transactions) {
        this.workItems = workItems;
        this.temporal = temporal;
        this.events = events;
        this.access = access;
        this.configurations = configurations;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    public WorkItemController.SignalResponse submit(String workItemId, String action, ActionRequest request,
                                                     Authentication actor) {
        var internalId = resolve(workItemId).workItemId();
        var prepared = transactions.execute(status -> prepare(internalId, action, request, actor));
        if (prepared == null) throw new IllegalStateException("手动动作准备失败");
        if (!prepared.dispatch()) {
            return new WorkItemController.SignalResponse(prepared.displayWorkItemId(), prepared.signalId(), "submitted");
        }
        try {
            temporal.signalCase(new TemporalCasePort.SignalCaseCommand(
                    prepared.caseId(), action, prepared.signalId(), prepared.context()));
        } catch (RuntimeException error) {
            events.append(new DomainEventService.AppendEvent(
                    DomainEventType.TemporalSignalFailed,
                    prepared.systemId(), prepared.caseId(), prepared.prdId(), internalId,
                    actor.getName(), "control-plane",
                    Map.of("signalName", action, "signalId", prepared.signalId(),
                            "reason", String.valueOf(error.getMessage())),
                    internalId, null, "signal-failed:" + prepared.submissionKey()));
            throw new IllegalStateException("Temporal signal 提交失败", error);
        }
        log.info("手动动作已提交 workItem={} action={} requestId={}", internalId, action, prepared.requestId());
        return new WorkItemController.SignalResponse(prepared.displayWorkItemId(), prepared.signalId(), "submitted");
    }

    public Availability availability(WorkItemProjection item, Authentication actor) {
        var runtime = runtime(item);
        var allowed = ACTIONS.getOrDefault(item.lifecycleStatus(), List.of());
        var canControl = access.canControl(item.systemId(), actor);
        var available = canControl ? allowed : allowed.stream()
                .filter(action -> requesterMayValidate(item, runtime, action, actor))
                .toList();
        available = available.stream().filter(action -> actionAvailable(action, runtime)).toList();
        if (runtime.pendingAction() != null) available = List.of();
        return new Availability(canControl || !available.isEmpty(), available, runtime.pendingAction(),
                runtime.releaseMode(), runtime.validationMode());
    }

    private PreparedSignal prepare(String workItemId, String action, ActionRequest rawRequest, Authentication actor) {
        var item = workItems.lockById(workItemId).orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
        if (item.deleted()) throw new IllegalArgumentException("工作项不存在");
        var request = rawRequest == null ? new ActionRequest(null, null, null, null, null) : rawRequest;
        var requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId().trim();
        if (!requestId.matches("[A-Za-z0-9_-]{8,80}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "requestId 格式不正确");
        }
        var note = text(request.note(), 2000, "note");
        var evidence = text(request.evidence(), 4000, "evidence");
        var signalId = action + "-" + requestId;
        var requestKey = "manual-action:" + workItemId + ":" + action + ":" + requestId;
        var runtime = runtime(item);
        requirePermission(item, runtime, action, actor);

        // 相同 requestId 始终复用同一个 signalId；只有明确的 Temporal 失败才重新投递。
        if (events.exists(requestKey) && !events.hasUnrecoveredSignalFailure(workItemId, signalId)) {
            return prepared(item, requestId, signalId, requestKey, note, evidence, Map.of(),
                    actor.getName(), false);
        }
        if (runtime.pendingAction() != null && !signalId.equals(runtime.pendingAction().signalId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTION_PENDING", "已有手动动作正在执行");
        }
        if (request.expectedStatus() != null && !request.expectedStatus().equals(item.lifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_WORK_ITEM", "工作项状态已变化，请刷新后重试");
        }
        if (request.expectedProjectionSequence() != null
                && request.expectedProjectionSequence() != item.lastAppliedSequence()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_WORK_ITEM", "工作项事件序号已变化，请刷新后重试");
        }
        if (!ACTIONS.getOrDefault(item.lifecycleStatus(), List.of()).contains(action)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTION_NOT_AVAILABLE", "当前阶段不能执行该操作");
        }
        if (!actionAvailable(action, runtime)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTION_NOT_AVAILABLE", "当前阻塞不支持该恢复操作");
        }

        var attempt = events.countSignalFailures(workItemId, signalId) + 1;
        var submissionKey = attempt == 1 ? requestKey : requestKey + ":retry:" + attempt;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("signalName", action);
        payload.put("signalId", signalId);
        payload.put("requestId", requestId);
        payload.put("expectedStatus", item.lifecycleStatus());
        payload.put("expectedProjectionSequence", item.lastAppliedSequence());
        payload.put("attempt", attempt);
        if (!note.isBlank()) payload.put("note", note);
        if (!evidence.isBlank()) payload.put("evidence", evidence);
        var signalContext = "rework_with_latest_config".equals(action)
                ? Map.<String, Object>of(
                        "agent_config_snapshot", latestAgentConfig(item.systemId()),
                        "resume_failed_stage", true)
                : Map.<String, Object>of();
        if (!signalContext.isEmpty()) payload.put("refreshConfiguration", true);
        events.append(new DomainEventService.AppendEvent(
                "owner_approved".equals(action) ? DomainEventType.OwnerApprovalSignalSubmitted : DomainEventType.TemporalSignalSubmitted,
                item.systemId(), item.caseId(), item.prdId(), workItemId,
                actor.getName(), "control-plane", payload, workItemId, null, submissionKey));
        return prepared(item, requestId, signalId, submissionKey, note, evidence, signalContext,
                actor.getName(), true);
    }

    private PreparedSignal prepared(WorkItemProjection item, String requestId, String signalId, String submissionKey,
                                    String note, String evidence, Map<String, Object> signalContext,
                                    String actorId, boolean dispatch) {
        var context = new LinkedHashMap<String, Object>();
        context.put("request_id", requestId);
        context.put("actor_id", actorId);
        if (!note.isBlank()) context.put("note", note);
        if (!evidence.isBlank()) context.put("evidence", evidence);
        context.putAll(signalContext);
        return new PreparedSignal(item.displayWorkItemId(), item.systemId(), item.prdId(), item.caseId(), requestId,
                signalId, submissionKey, context, dispatch);
    }

    private void requirePermission(WorkItemProjection item, RuntimeState runtime, String action, Authentication actor) {
        if (requesterMayValidate(item, runtime, action, actor)) {
            access.requireMember(item.systemId(), actor);
            return;
        }
        access.requireOwnerOrAdmin(item.systemId(), actor);
    }

    private boolean requesterMayValidate(WorkItemProjection item, RuntimeState runtime, String action,
                                         Authentication actor) {
        return "manual".equals(runtime.validationMode()) && VALIDATION_ACTIONS.contains(action)
                && actor.getName().equals(item.createdBy());
    }

    private boolean actionAvailable(String action, RuntimeState runtime) {
        return ACTION_GUARDS.getOrDefault(action, ALWAYS_AVAILABLE).test(runtime);
    }

    private RuntimeState runtime(WorkItemProjection item) {
        String releaseMode = "";
        String validationMode = "";
        String failedPhase = "";
        PendingAction pending = null;
        var timeline = events.findByWorkItemId(item.workItemId());
        var completedSignals = new HashSet<String>();
        for (var event : timeline) {
            var payload = payload(event);
            if ("OwnerApprovalRequested".equals(event.eventType())) {
                releaseMode = string(payload.get("releaseMode"));
                validationMode = string(payload.get("validationMode"));
            }
            if ("TemporalActionCompleted".equals(event.eventType())) {
                completedSignals.add(string(payload.get("signalId")));
            }
            if ("WorkerBlocked".equals(event.eventType())) {
                failedPhase = string(payload.get("failedPhase"));
            }
            if ("ReworkStarted".equals(event.eventType())) {
                failedPhase = "";
            }
        }
        for (var event : timeline) {
            var payload = payload(event);
            if (List.of("OwnerApprovalSignalSubmitted", "TemporalSignalSubmitted").contains(event.eventType())) {
                var signalId = string(payload.get("signalId"));
                pending = completedSignals.contains(signalId) ? null
                        : new PendingAction(string(payload.get("signalName")), signalId, event.createdAt());
            } else if (pending != null && List.of("TemporalSignalFailed", "TemporalActionCompleted").contains(event.eventType())
                    && pending.signalId().equals(string(payload.get("signalId")))) {
                pending = null;
            }
        }
        return new RuntimeState(releaseMode, validationMode, pending, failedPhase);
    }

    private Map<String, Object> latestAgentConfig(String systemId) {
        var config = configurations.internal(systemId);
        var snapshot = new TemporalCasePort.AgentConfigSnapshot(
                config.modelProfiles().stream().map(profile -> new TemporalCasePort.ModelProfileSnapshot(
                        profile.id(), profile.name(), profile.provider(), profile.baseUrl(), profile.model(),
                        profile.supportsVision())).toList(),
                config.agents().stream().map(agent -> new TemporalCasePort.AgentSnapshot(
                        agent.name(), agent.kind(), agent.engine(), agent.modelProfileRef(), agent.pathScope(),
                        agent.prompt(), agent.maxTurns(), agent.timeoutSeconds())).toList());
        // Temporal Python 入参固定使用 snake_case，且快照不包含 API Key。
        return objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .convertValue(snapshot, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private Map<String, Object> payload(DomainEventRecord event) {
        try {
            return objectMapper.readValue(event.payloadJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private WorkItemProjection resolve(String workItemId) {
        return workItems.findById(workItemId).or(() -> workItems.findByDisplayWorkItemId(workItemId))
                .filter(item -> !item.deleted())
                .orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
    }

    private String text(String value, int maxLength, String field) {
        var result = value == null ? "" : value.trim();
        if (result.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACTION_CONTEXT", field + " 内容过长");
        }
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ActionRequest(String requestId, String expectedStatus, Long expectedProjectionSequence,
                                String note, String evidence) {
    }

    public record PendingAction(String action, String signalId, Instant submittedAt) {
    }

    public record Availability(boolean canAct, List<String> actions, PendingAction pendingAction,
                               String releaseMode, String validationMode) {
    }

    private record RuntimeState(String releaseMode, String validationMode, PendingAction pendingAction,
                                String failedPhase) {
        boolean phaseRetrySupported() {
            return !failedPhase.isBlank();
        }

        boolean configurationRefreshSupported() {
            return CONFIG_REFRESH_PHASES.contains(failedPhase);
        }
    }

    private record PreparedSignal(String displayWorkItemId, String systemId, String prdId, String caseId,
                                  String requestId, String signalId, String submissionKey,
                                  Map<String, Object> context, boolean dispatch) {
    }
}
