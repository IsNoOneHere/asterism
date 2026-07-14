package com.asterism.workitem;

import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import org.springframework.security.core.Authentication;
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

    public WorkItemController(WorkItemProjectionRepository workItems, TemporalCasePort temporal, DomainEventService events, SystemAccessService access) {
        this.workItems = workItems;
        this.temporal = temporal;
        this.events = events;
        this.access = access;
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
        return events.findByWorkItemId(workItemId);
    }

    @PostMapping("/{workItemId}/owner-approval")
    SignalResponse ownerApproval(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        var signalId = "owner-approved-" + workItemId;
        if (events.exists(signalId) && !events.hasUnrecoveredSignalFailure(workItemId, signalId)) {
            return new SignalResponse(workItemId, signalId, "submitted");
        }
        var attempt = events.countSignalFailures(workItemId, signalId) + 1;
        var submissionKey = attempt == 1 ? signalId : signalId + ":retry:" + attempt;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.OwnerApprovalSignalSubmitted,
                item.systemId(),
                item.caseId(),
                item.prdId(),
                workItemId,
                actor.getName(),
                "control-plane",
                Map.of("signalName", "owner_approved", "signalId", signalId, "attempt", attempt),
                workItemId,
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
                    workItemId,
                    actor.getName(),
                    "control-plane",
                    Map.of("signalName", "owner_approved", "signalId", signalId, "reason", error.getMessage()),
                    workItemId,
                    null,
                    "signal-failed:" + submissionKey));
            throw new IllegalStateException("Temporal signal 提交失败", error);
        }
        return new SignalResponse(workItemId, signalId, "submitted");
    }

    @PostMapping("/{workItemId}/signals/{signalName}")
    SignalResponse submitSignal(@PathVariable String workItemId, @PathVariable String signalName, Authentication actor) {
        var item = find(workItemId);
        access.requireOwnerOrAdmin(item.systemId(), actor);
        var attempt = events.countSubmittedSignals(workItemId, signalName) + 1;
        var signalId = signalName + "-" + workItemId + "-" + attempt;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.TemporalSignalSubmitted,
                item.systemId(),
                item.caseId(),
                item.prdId(),
                workItemId,
                actor.getName(),
                "control-plane",
                Map.of("signalName", signalName, "signalId", signalId, "attempt", attempt),
                workItemId,
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
                    workItemId,
                    actor.getName(),
                    "control-plane",
                    Map.of("signalName", signalName, "signalId", signalId, "reason", error.getMessage()),
                    workItemId,
                    null,
                    "signal-failed:" + signalId));
            throw new IllegalStateException("Temporal signal 提交失败", error);
        }
        return new SignalResponse(workItemId, signalId, "submitted");
    }

    public record SignalResponse(String workItemId, String signalId, String status) {
    }

    private WorkItemProjection find(String workItemId) {
        return workItems.findById(workItemId).orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
    }

    private WorkItemView view(WorkItemProjection item, Authentication actor) {
        var canControl = access.canControl(item.systemId(), actor);
        return new WorkItemView(item.workItemId(), item.systemId(), item.prdId(), item.caseId(), item.title(),
                item.lifecycleStatus(), item.approvalStatus(), item.executionAllowed(), item.currentStage(), item.waitingFor(),
                item.ownerUserId(), item.createdBy(), item.createdAt(), item.updatedAt(), canControl,
                canControl ? actions(item.lifecycleStatus()) : List.of());
    }

    private List<String> actions(String status) {
        return switch (status) {
            case "waiting_owner_approval" -> List.of("owner_approved", "owner_rejected", "cancel_case");
            case "activated" -> List.of("start_modification", "cancel_case");
            case "worker_blocked", "patch_rejected", "validation_failed" -> List.of("rework", "cancel_case");
            case "modification_completed" -> List.of("patch_apply_approved", "patch_apply_rejected", "cancel_case");
            case "patch_applied" -> List.of("validation_passed", "validation_rejected", "cancel_case");
            case "validation_passed" -> List.of("release_approved", "cancel_case");
            default -> List.of();
        };
    }

    public record WorkItemView(String workItemId, String systemId, String prdId, String caseId, String title,
                               String lifecycleStatus, String approvalStatus, boolean executionAllowed,
                               String currentStage, String waitingFor, String ownerUserId, String createdBy,
                               Instant createdAt, Instant updatedAt, boolean canControl, List<String> availableActions) {
    }
}
