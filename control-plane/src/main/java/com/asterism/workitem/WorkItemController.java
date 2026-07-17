package com.asterism.workitem;

import com.asterism.common.ApiException;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.git.GitIntegrationService;
import com.asterism.git.GitLabClient;
import com.asterism.knowledge.KnowledgeMatchService.SuspectedTarget;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import com.asterism.prd.PrdSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/v5/work-items")
public class WorkItemController {
    private final WorkItemProjectionRepository workItems;
    private final TemporalCasePort temporal;
    private final DomainEventService events;
    private final SystemAccessService access;
    private final PrdSessionRepository prdSessions;
    private final ObjectMapper objectMapper;
    private final GitIntegrationService git;
    private final GitLabClient gitLab;

    public WorkItemController(WorkItemProjectionRepository workItems, TemporalCasePort temporal, DomainEventService events,
                              SystemAccessService access, PrdSessionRepository prdSessions, ObjectMapper objectMapper,
                              GitIntegrationService git, GitLabClient gitLab) {
        this.workItems = workItems;
        this.temporal = temporal;
        this.events = events;
        this.access = access;
        this.prdSessions = prdSessions;
        this.objectMapper = objectMapper;
        this.git = git;
        this.gitLab = gitLab;
    }

    @GetMapping
    List<WorkItemView> list(@RequestParam(required = false) String systemId,
                            @RequestParam(defaultValue = "system") String scope,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "updated_desc") String sort,
                            Authentication actor) {
        Iterable<WorkItemProjection> source;
        if ("system".equals(scope)) {
            if (systemId == null || systemId.isBlank()) throw new IllegalArgumentException("systemId 不能为空");
            access.requireMember(systemId, actor);
            source = workItems.findBySystemIdAndDeletedFalse(systemId);
        } else {
            source = workItems.findAll();
        }
        var keyword = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        var views = StreamSupport.stream(source.spliterator(), false)
                .filter(item -> !item.deleted() && access.canAccess(item.systemId(), actor))
                .map(item -> view(item, actor))
                .filter(item -> !"mine".equals(scope) || !item.availableActions().isEmpty())
                .filter(item -> status == null || status.isBlank() || status.equals(item.lifecycleStatus()))
                .filter(item -> keyword.isBlank() || item.workItemId().toLowerCase(Locale.ROOT).contains(keyword)
                        || (item.title() != null && item.title().toLowerCase(Locale.ROOT).contains(keyword)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Comparator<WorkItemView> comparator = switch (sort) {
            case "created_asc" -> Comparator.comparing(WorkItemView::createdAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "created_desc" -> Comparator.comparing(WorkItemView::createdAt, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(WorkItemView::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        views.sort(comparator);
        return views;
    }

    @GetMapping("/{workItemId}")
    WorkItemView detail(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireMember(item.systemId(), actor);
        return view(item, actor);
    }

    @GetMapping("/{workItemId}/events")
    Iterable<DomainEventRecord> events(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireMember(item.systemId(), actor);
        return events.findByWorkItemId(item.workItemId());
    }

    @PostMapping("/{workItemId}/owner-approval")
    SignalResponse ownerApproval(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        var internalId = item.workItemId();
        var signalId = "owner-approved-" + internalId;
        if (events.exists(signalId) && !events.hasUnrecoveredSignalFailure(internalId, signalId)) {
            return new SignalResponse(item.displayWorkItemId(), signalId, "submitted");
        }
        var attempt = events.countSignalFailures(internalId, signalId) + 1;
        var submissionKey = attempt == 1 ? signalId : signalId + ":retry:" + attempt;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.OwnerApprovalSignalSubmitted,
                item.systemId(),
                item.caseId(),
                item.prdId(),
                internalId,
                actor.getName(),
                "control-plane",
                Map.of("signalName", "owner_approved", "signalId", signalId, "attempt", attempt),
                internalId,
                null,
                submissionKey));
        try {
            // 事件已提交后再发 Temporal signal，避免数据库事务和外部调用互相污染。
            temporal.signalCase(new TemporalCasePort.SignalCaseCommand(item.caseId(), "owner_approved", signalId));
        } catch (RuntimeException error) {
            events.append(new DomainEventService.AppendEvent(
                    DomainEventType.TemporalSignalFailed,
                    item.systemId(),
                    item.caseId(),
                    item.prdId(),
                    internalId,
                    actor.getName(),
                    "control-plane",
                    Map.of("signalName", "owner_approved", "signalId", signalId, "reason", error.getMessage()),
                    internalId,
                    null,
                    "signal-failed:" + submissionKey));
            throw new IllegalStateException("Temporal signal 提交失败", error);
        }
        return new SignalResponse(item.displayWorkItemId(), signalId, "submitted");
    }

    @PostMapping("/{workItemId}/signals/{signalName}")
    SignalResponse submitSignal(@PathVariable String workItemId, @PathVariable String signalName, Authentication actor) {
        var item = find(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        var internalId = item.workItemId();
        var attempt = events.countSubmittedSignals(internalId, signalName) + 1;
        var signalId = signalName + "-" + internalId + "-" + attempt;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.TemporalSignalSubmitted,
                item.systemId(),
                item.caseId(),
                item.prdId(),
                internalId,
                actor.getName(),
                "control-plane",
                Map.of("signalName", signalName, "signalId", signalId, "attempt", attempt),
                internalId,
                null,
                signalId));
        try {
            temporal.signalCase(new TemporalCasePort.SignalCaseCommand(item.caseId(), signalName, signalId));
        } catch (RuntimeException error) {
            events.append(new DomainEventService.AppendEvent(
                    DomainEventType.TemporalSignalFailed,
                    item.systemId(),
                    item.caseId(),
                    item.prdId(),
                    internalId,
                    actor.getName(),
                    "control-plane",
                    Map.of("signalName", signalName, "signalId", signalId, "reason", error.getMessage()),
                    internalId,
                    null,
                    "signal-failed:" + signalId));
            throw new IllegalStateException("Temporal signal 提交失败", error);
        }
        return new SignalResponse(item.displayWorkItemId(), signalId, "submitted");
    }

    @PostMapping("/{workItemId}/merge-status/check")
    SignalResponse checkMergeStatus(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        if (!"waiting_merge".equals(item.lifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_WAITING_MERGE", "工作项当前不在等待合并状态");
        }
        var config = git.internal(item.systemId());
        var repos = config.repos().stream().collect(java.util.stream.Collectors.toMap(
                GitIntegrationService.RepoConfig::repoId, value -> value));
        var mergeRequests = latestMergeRequests(item.workItemId());
        if (mergeRequests.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_NOT_FOUND", "工作项没有可核验的 MR");
        }
        var unmerged = mergeRequests.stream().filter(mr -> {
            var repo = repos.get(mr.repo());
            return repo == null || !"merged".equals(gitLab.mergeRequest(
                    config.baseUrl(), config.token(), repo.gitlabProject(), mr.iid()).state());
        }).map(mr -> mr.repo() + "!" + mr.iid()).toList();
        if (!unmerged.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_NOT_MERGED", "仍有 MR 未合并", unmerged);
        }
        return submitSignal(item.workItemId(), "check_merge_status", actor);
    }

    public record SignalResponse(String workItemId, String signalId, String status) {
    }

    private WorkItemProjection find(String workItemId) {
        return workItems.findById(workItemId)
                .or(() -> workItems.findByDisplayWorkItemId(workItemId))
                .orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
    }

    private WorkItemView view(WorkItemProjection item, Authentication actor) {
        var canControl = access.canControl(item.systemId(), actor);
        return new WorkItemView(item.displayWorkItemId(), item.systemId(), item.prdId(), item.caseId(), item.title(),
                item.lifecycleStatus(), item.approvalStatus(), item.executionAllowed(), item.currentStage(), item.waitingFor(),
                item.ownerUserId(), item.createdBy(), item.createdAt(), item.updatedAt(), canControl,
                canControl ? actions(item.lifecycleStatus()) : List.of(), targets(item.prdId()));
    }

    private List<SuspectedTarget> targets(String prdId) {
        if (prdId == null) return List.of();
        var session = prdSessions.findById(prdId).orElse(null);
        if (session == null) return List.of();
        try {
            var draft = objectMapper.readValue(session.draftJson(), new TypeReference<Map<String, Object>>() {});
            return objectMapper.convertValue(draft.getOrDefault("targets", List.of()),
                    new TypeReference<List<SuspectedTarget>>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private List<String> actions(String status) {
        return switch (status) {
            case "waiting_owner_approval" -> List.of("owner_approved", "owner_rejected", "cancel_case");
            case "activated" -> List.of("start_modification", "cancel_case");
            case "worker_blocked", "patch_rejected", "validation_failed" -> List.of("rework", "cancel_case");
            case "modification_completed" -> List.of("patch_apply_approved", "patch_apply_rejected", "cancel_case");
            case "patch_applied" -> List.of("validation_passed", "validation_rejected", "cancel_case");
            case "validation_passed" -> List.of("release_approved", "cancel_case");
            case "waiting_merge" -> List.of("check_merge_status", "rework", "cancel_case");
            default -> List.of();
        };
    }

    private List<MergeRequestReference> latestMergeRequests(String workItemId) {
        var timeline = events.findByWorkItemId(workItemId);
        String causation = null;
        for (var event : timeline) {
            if ("MergeRequestCreated".equals(event.eventType()) && event.causationId() != null) {
                causation = event.causationId().split(":mr:", 2)[0];
            }
        }
        if (causation == null) return List.of();
        var prefix = causation + ":mr:";
        var result = new ArrayList<MergeRequestReference>();
        for (var event : timeline) {
            if (!"MergeRequestCreated".equals(event.eventType()) || event.causationId() == null
                    || !event.causationId().startsWith(prefix)) continue;
            try {
                var payload = objectMapper.readTree(event.payloadJson());
                result.add(new MergeRequestReference(payload.path("repo").asText(), payload.path("mrIid").asInt()));
            } catch (JsonProcessingException ignored) {
                // 历史坏事件不参与人工核验，Temporal 轮询仍会继续。
            }
        }
        return result;
    }

    private record MergeRequestReference(String repo, int iid) {
    }

    public record WorkItemView(String workItemId, String systemId, String prdId, String caseId, String title,
                               String lifecycleStatus, String approvalStatus, boolean executionAllowed,
                               String currentStage, String waitingFor, String ownerUserId, String createdBy,
                               Instant createdAt, Instant updatedAt, boolean canControl, List<String> availableActions,
                               List<SuspectedTarget> targets) {
    }
}
