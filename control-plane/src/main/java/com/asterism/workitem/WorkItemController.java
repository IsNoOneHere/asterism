package com.asterism.workitem;

import com.asterism.attachment.AttachmentRepository;
import com.asterism.common.ApiException;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventRecord;
import com.asterism.identity.SystemAccessService;
import com.asterism.git.GitIntegrationService;
import com.asterism.git.GitLabClient;
import com.asterism.knowledge.KnowledgeMatchService.SuspectedTarget;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.prd.PrdSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(WorkItemController.class);
    private final WorkItemProjectionRepository workItems;
    private final DomainEventService events;
    private final WorkItemActionService actions;
    private final SystemAccessService access;
    private final PrdSessionRepository prdSessions;
    private final ObjectMapper objectMapper;
    private final GitIntegrationService git;
    private final GitLabClient gitLab;
    private final AttachmentRepository attachments;

    public WorkItemController(WorkItemProjectionRepository workItems, DomainEventService events, WorkItemActionService actions,
                              SystemAccessService access, PrdSessionRepository prdSessions, ObjectMapper objectMapper,
                              GitIntegrationService git, GitLabClient gitLab, AttachmentRepository attachments) {
        this.workItems = workItems;
        this.events = events;
        this.actions = actions;
        this.access = access;
        this.prdSessions = prdSessions;
        this.objectMapper = objectMapper;
        this.git = git;
        this.gitLab = gitLab;
        this.attachments = attachments;
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

    @GetMapping("/{workItemId}/attachments")
    List<WorkItemAttachmentView> attachments(@PathVariable String workItemId, Authentication actor) {
        var item = find(workItemId);
        access.requireMember(item.systemId(), actor);
        if (item.prdId() == null) return List.of();
        // 工作项只返回展示所需字段，不暴露附件存储路径和摘要。
        return attachments.findByPrdIdAndSystemId(item.prdId(), item.systemId()).stream()
                .map(attachment -> new WorkItemAttachmentView(attachment.attachmentId(), attachment.filename(),
                        attachment.contentType(), attachment.sizeBytes()))
                .toList();
    }

    @DeleteMapping("/{workItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String workItemId, Authentication actor) {
        var item = findAny(workItemId);
        access.requireMember(item.systemId(), actor);
        if (!actor.getName().equals(item.createdBy())) access.requireOwnerOrAdmin(item.systemId(), actor);
        if (!item.deleted()) workItems.save(deleted(item));
        log.info("工作项已删除 workItem={} actor={}", item.workItemId(), actor.getName());
    }

    @PostMapping("/{workItemId}/owner-approval")
    SignalResponse ownerApproval(@PathVariable String workItemId,
                                 @RequestBody(required = false) WorkItemActionService.ActionRequest request,
                                 Authentication actor) {
        return actions.submit(workItemId, "owner_approved", request, actor);
    }

    @PostMapping("/{workItemId}/signals/{signalName}")
    SignalResponse submitSignal(@PathVariable String workItemId, @PathVariable String signalName,
                                @RequestBody(required = false) WorkItemActionService.ActionRequest request,
                                Authentication actor) {
        return actions.submit(workItemId, signalName, request, actor);
    }

    @PostMapping("/{workItemId}/merge-status/check")
    SignalResponse checkMergeStatus(@PathVariable String workItemId,
                                    @RequestBody(required = false) WorkItemActionService.ActionRequest request,
                                    Authentication actor) {
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
            var project = mr.project();
            if (project.isBlank()) {
                var repo = repos.get(mr.repo());
                project = repo == null ? "" : repo.gitlabProject();
            }
            return project.isBlank() || !"merged".equals(gitLab.mergeRequest(
                    config.baseUrl(), config.token(), project, mr.iid()).state());
        }).map(mr -> mr.repo() + "!" + mr.iid()).toList();
        if (!unmerged.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_NOT_MERGED", "仍有 MR 未合并", unmerged);
        }
        return actions.submit(item.workItemId(), "check_merge_status", request, actor);
    }

    public record SignalResponse(String workItemId, String signalId, String status) {
    }

    private WorkItemProjection find(String workItemId) {
        var item = findAny(workItemId);
        if (item.deleted()) throw new IllegalArgumentException("工作项不存在");
        return item;
    }

    private WorkItemProjection findAny(String workItemId) {
        return workItems.findById(workItemId)
                .or(() -> workItems.findByDisplayWorkItemId(workItemId))
                .orElseThrow(() -> new IllegalArgumentException("工作项不存在"));
    }

    private WorkItemProjection deleted(WorkItemProjection item) {
        // 删除与生命周期解耦，只隐藏业务入口，不改写工作流状态与事件。
        return new WorkItemProjection(item.workItemId(), item.displayWorkItemId(), item.systemId(), item.prdId(),
                item.caseId(), item.title(), item.lifecycleStatus(), item.approvalStatus(), item.executionAllowed(),
                item.currentStage(), item.waitingFor(), item.ownerUserId(), true, item.lastAppliedSequence(),
                item.activatedAt(), item.completedAt(), item.createdBy(), item.createdAt(), Instant.now());
    }

    private WorkItemView view(WorkItemProjection item, Authentication actor) {
        var availability = actions.availability(item, actor);
        return new WorkItemView(item.displayWorkItemId(), item.systemId(), item.prdId(), item.caseId(), item.title(),
                item.lifecycleStatus(), item.approvalStatus(), item.executionAllowed(),
                availability.currentStage(), availability.waitingFor(),
                item.ownerUserId(), item.createdBy(), item.createdAt(), item.updatedAt(), canDelete(item, actor), availability.canAct(),
                availability.actions(), item.lastAppliedSequence(), availability.pendingAction(),
                availability.releaseMode(), availability.validationMode(), targets(item.prdId()));
    }

    private boolean canDelete(WorkItemProjection item, Authentication actor) {
        return actor.getName().equals(item.createdBy()) || access.canControl(item.systemId(), actor);
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
                result.add(new MergeRequestReference(payload.path("repo").asText(), payload.path("project").asText(),
                        payload.path("mrIid").asInt()));
            } catch (JsonProcessingException ignored) {
                // 历史坏事件不参与人工核验，Temporal 轮询仍会继续。
            }
        }
        return result;
    }

    private record MergeRequestReference(String repo, String project, int iid) {
    }

    public record WorkItemView(String workItemId, String systemId, String prdId, String caseId, String title,
                               String lifecycleStatus, String approvalStatus, boolean executionAllowed,
                               String currentStage, String waitingFor, String ownerUserId, String createdBy,
                               Instant createdAt, Instant updatedAt, boolean canDelete, boolean canControl,
                               List<String> availableActions,
                               long lastAppliedSequence, WorkItemActionService.PendingAction pendingAction,
                               String releaseMode, String validationMode,
                               List<SuspectedTarget> targets) {
    }

    public record WorkItemAttachmentView(String attachmentId, String filename, String contentType, long sizeBytes) {
    }
}
