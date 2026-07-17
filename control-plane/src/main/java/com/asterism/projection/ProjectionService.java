package com.asterism.projection;

import com.asterism.event.DomainEventRecord;
import com.asterism.prd.PrdSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class ProjectionService {
    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);
    private static final Map<String, LifecycleStatus> EVENT_TARGETS = Map.ofEntries(
            Map.entry("OwnerApprovalRequested", LifecycleStatus.waiting_owner_approval),
            Map.entry("OwnerApprovalSignalSubmitted", LifecycleStatus.waiting_owner_approval),
            Map.entry("WorkItemActivated", LifecycleStatus.activated),
            Map.entry("ReworkStarted", LifecycleStatus.activated),
            Map.entry("WorkItemRejected", LifecycleStatus.rejected),
            Map.entry("ModificationCompleted", LifecycleStatus.modification_completed),
            Map.entry("WorkerBlocked", LifecycleStatus.worker_blocked),
            Map.entry("PatchApplyBlocked", LifecycleStatus.worker_blocked),
            Map.entry("PatchApplied", LifecycleStatus.patch_applied),
            Map.entry("PatchRejected", LifecycleStatus.patch_rejected),
            Map.entry("ValidationPassed", LifecycleStatus.validation_passed),
            Map.entry("ValidationFailed", LifecycleStatus.validation_failed),
            Map.entry("MergeRequestCreated", LifecycleStatus.waiting_merge),
            Map.entry("MergeRequestClosed", LifecycleStatus.worker_blocked),
            Map.entry("ReleaseCompleted", LifecycleStatus.completed),
            Map.entry("CaseCancelled", LifecycleStatus.cancelled));

    private final WorkItemProjectionStore workItems;
    private final PrdSessionRepository prdSessions;

    public ProjectionService(WorkItemProjectionStore workItems, PrdSessionRepository prdSessions) {
        this.workItems = workItems;
        this.prdSessions = prdSessions;
    }

    public void apply(DomainEventRecord event) {
        if (event.workItemId() == null || !EVENT_TARGETS.containsKey(event.eventType())) {
            return;
        }
        var current = workItems.findById(event.workItemId()).orElse(null);
        if (current != null && event.sequence() <= current.lastAppliedSequence()) {
            log.warn("跳过乱序旧事件 sequence={} lastApplied={} workItem={}",
                    event.sequence(), current.lastAppliedSequence(), event.workItemId());
            return;
        }
        var target = EVENT_TARGETS.get(event.eventType());
        var from = current == null ? LifecycleStatus.allocated : LifecycleStatus.valueOf(current.lifecycleStatus());
        if (!LifecycleStateMachine.canMove(from, target)) {
            log.warn("非法生命周期迁移 workItem={} from={} to={} event={}",
                    event.workItemId(), from, target, event.eventType());
            return;
        }
        var updatedAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        workItems.save(nextProjection(event, current, target));
        prdSessions.updateLifecycleStatus(event.workItemId(), target.name(), updatedAt);
        log.info("投影已更新 workItem={} status={} sequence={}", event.workItemId(), target, event.sequence());
    }

    private WorkItemProjection nextProjection(DomainEventRecord event, WorkItemProjection current, LifecycleStatus target) {
        var now = event.createdAt() == null ? Instant.now() : event.createdAt();
        var first = current == null;
        var title = first ? prdTitle(event) : current.title();
        return new WorkItemProjection(
                event.workItemId(),
                first ? event.workItemId() : current.displayWorkItemId(),
                event.systemId(),
                event.prdId(),
                event.caseId(),
                title,
                target.name(),
                approvalStatus(event.eventType(), current),
                target == LifecycleStatus.activated || target == LifecycleStatus.modification_completed || target == LifecycleStatus.patch_applied,
                stage(target),
                waitingFor(target),
                first ? event.actorId() : current.ownerUserId(),
                target == LifecycleStatus.cancelled,
                event.sequence(),
                target == LifecycleStatus.activated ? now : first ? null : current.activatedAt(),
                target == LifecycleStatus.completed ? now : first ? null : current.completedAt(),
                first ? event.actorId() : current.createdBy(),
                first ? now : current.createdAt(),
                now);
    }

    private String prdTitle(DomainEventRecord event) {
        if (event.prdId() == null) return event.workItemId();
        // 工作项标题沿用用户确认的 PRD 标题，避免把内部关联 ID 暴露在列表中。
        return prdSessions.findById(event.prdId())
                .map(session -> session.title())
                .filter(title -> !title.isBlank())
                .orElse(event.workItemId());
    }

    private String approvalStatus(String eventType, WorkItemProjection current) {
        if ("OwnerApprovalSignalSubmitted".equals(eventType)) {
            return "submitted";
        }
        if ("WorkItemActivated".equals(eventType)) {
            return "approved";
        }
        return current == null ? "pending" : current.approvalStatus();
    }

    private String stage(LifecycleStatus status) {
        return switch (status) {
            case waiting_owner_approval -> "等待负责人审批";
            case activated -> "Worker 已激活";
            case modification_completed -> "等待确认应用 patch";
            case worker_blocked -> "Worker 阻塞";
            case patch_applied -> "等待验证";
            case patch_rejected -> "等待重新修改";
            case validation_passed -> "等待上线确认";
            case validation_failed -> "等待重改";
            case waiting_merge -> "等待 GitLab 合并";
            case completed -> "已完成";
            case cancelled -> "已取消";
            case rejected -> "已拒绝";
            case allocated -> "已分配";
        };
    }

    private String waitingFor(LifecycleStatus status) {
        return switch (status) {
            case waiting_owner_approval, modification_completed, patch_rejected, validation_passed, validation_failed -> "owner";
            case waiting_merge -> "gitlab";
            case activated, worker_blocked, patch_applied -> "worker";
            default -> "";
        };
    }
}
