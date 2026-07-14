package com.asterism.prd;

import com.asterism.common.ApiException;
import com.asterism.attachment.AttachmentService;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.system.SystemProfileRepository;
import com.asterism.temporal.TemporalCasePort;
import com.asterism.vision.ImageAnalysisService;
import com.asterism.vision.UiObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v5")
public class PrdController {
    private static final Logger log = LoggerFactory.getLogger(PrdController.class);

    private final PrdSessionRepository sessions;
    private final ConversationMessageRepository conversationMessages;
    private final ProductAgentPort productAgent;
    private final DomainEventService events;
    private final TemporalCasePort temporal;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactions;
    private final SystemAccessService access;
    private final SystemProfileRepository systems;
    private final MemoryItemRepository memories;
    private final JdbcAggregateTemplate aggregate;
    private final WorkItemIdGenerator workItemIds;
    private final ExecutionReadinessService readiness;
    private final AttachmentService attachments;
    private final ImageAnalysisService imageAnalysis;
    private final KnowledgeMatchService knowledge;

    public PrdController(
            PrdSessionRepository sessions,
            ConversationMessageRepository conversationMessages,
            ProductAgentPort productAgent,
            DomainEventService events,
            TemporalCasePort temporal,
            ObjectMapper objectMapper,
            TransactionOperations transactions,
            SystemAccessService access,
            SystemProfileRepository systems,
            MemoryItemRepository memories,
            JdbcAggregateTemplate aggregate,
            WorkItemIdGenerator workItemIds,
            ExecutionReadinessService readiness,
            AttachmentService attachments,
            ImageAnalysisService imageAnalysis,
            KnowledgeMatchService knowledge) {
        this.sessions = sessions;
        this.conversationMessages = conversationMessages;
        this.productAgent = productAgent;
        this.events = events;
        this.temporal = temporal;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.access = access;
        this.systems = systems;
        this.memories = memories;
        this.aggregate = aggregate;
        this.workItemIds = workItemIds;
        this.readiness = readiness;
        this.attachments = attachments;
        this.imageAnalysis = imageAnalysis;
        this.knowledge = knowledge;
    }

    @PostMapping("/systems/{systemId}/prd/messages")
    @Transactional
    PrdMessageResponse message(@PathVariable String systemId, @Valid @RequestBody PrdMessageRequest request, Authentication actor) {
        var now = Instant.now();
        var attachmentIds = request.attachmentIds() == null ? List.<String>of() : request.attachmentIds();
        var userContent = request.content() == null ? "" : request.content().trim();
        if (attachmentIds.size() > 3) throw new IllegalArgumentException("每条消息最多上传 3 张图片");
        if (attachmentIds.isEmpty() && userContent.isBlank()) throw new IllegalArgumentException("消息内容和图片不能同时为空");
        var current = request.prdId() == null ? null : sessions.findById(request.prdId()).orElse(null);
        if (current == null) {
            access.requireMember(systemId, actor);
        } else {
            access.requireMember(current.systemId(), actor);
            if (!current.systemId().equals(systemId)) {
                throw new ApiException(HttpStatus.CONFLICT, "PRD_SYSTEM_MISMATCH", "PRD 所属系统不能变更");
            }
        }
        var prdId = current == null ? "prd-" + UUID.randomUUID() : current.prdId();
        var conversationId = current == null ? "conv-" + prdId : current.conversationId();
        var turn = conversationMessages.countByConversationIdAndSenderType(conversationId, "user") + 1;
        var currentDraft = current == null ? Map.<String, Object>of() : readMap(current.draftJson());
        var currentMissing = current == null ? List.<String>of() : readList(current.missingFields());
        var history = current == null ? List.<ConversationMessage>of() : conversationMessages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        var approvedMemories = memories.findBySystemIdAndStatus(systemId, "approved")
                .stream()
                .limit(5)
                .map(memory -> memory.content())
                .toList();
        var analysis = analyze(systemId, attachmentIds);
        var agentContent = userContent + (analysis.observations().isEmpty() ? "" : "\n截图观察："
                + analysis.observations().stream().map(UiObservation::contextText).collect(java.util.stream.Collectors.joining("\n")));
        var result = productAgent.updateDraft(systemId, agentContent, currentDraft, currentMissing, history, approvedMemories);
        var draft = new LinkedHashMap<>(result.draft());
        preserveTargets(currentDraft, draft);
        var anchors = analysis.observations().stream().flatMap(observation -> observation.anchors().stream()).toList();
        var match = anchors.isEmpty() ? new KnowledgeMatchService.MatchResult(List.of(), false) : knowledge.match(systemId, anchors);
        if (!match.targets().isEmpty()) draft.put("suspectedTargets", match.targets());
        var assistantMessage = assistantMessage(result.assistantMessage(), analysis.failed(), match);
        var status = result.missingFields().isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var title = current == null ? result.title() : current.title();
        var goal = current == null ? agentContent : current.goal();
        var session = new PrdSession(
                prdId,
                systemId,
                conversationId,
                current == null ? null : current.workItemId(),
                current == null ? null : current.caseId(),
                title,
                goal,
                json(draft),
                json(result.missingFields()),
                status,
                current == null ? actor.getName() : current.createdBy(),
                current == null ? null : current.confirmedBy(),
                current == null ? null : current.confirmedAt(),
                current == null ? now : current.createdAt(),
                now);
        if (current == null) {
            aggregate.insert(session);
        } else {
            aggregate.update(session);
        }
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), conversationId, systemId, prdId,
                "user", userContent, json(attachmentIds), json(analysis.observations()), actor.getName(), now));
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), conversationId, systemId, prdId,
                "assistant", assistantMessage, "[]", "[]", "product-agent", now));
        append(DomainEventType.UserMessageReceived, systemId, null, prdId, null, actor.getName(),
                "UserMessageReceived:" + prdId + ":" + turn, Map.of("content", userContent, "turn", turn));
        append(DomainEventType.PRDUpdated, systemId, null, prdId, null, actor.getName(),
                "PRDUpdated:" + prdId + ":" + turn, Map.of("title", title, "status", status, "turn", turn));
        if (!result.missingFields().isEmpty()) {
            append(DomainEventType.ClarificationRequested, systemId, null, prdId, null, "product-agent",
                    "ClarificationRequested:" + prdId + ":" + turn,
                    Map.of("missingFields", result.missingFields()));
        }
        return new PrdMessageResponse(session.prdId(), conversationId, status, assistantMessage, result.missingFields(), draft);
    }

    @PostMapping("/prd-sessions/{prdId}/targets/confirm")
    @Transactional
    TargetConfirmationResponse confirmTargets(@PathVariable String prdId, @RequestBody TargetConfirmationRequest request,
                                              Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
        var draft = new LinkedHashMap<>(readMap(current.draftJson()));
        var entryIds = request.entryIds() == null ? List.<String>of() : request.entryIds();
        var selected = objectMapper.convertValue(draft.getOrDefault("suspectedTargets", List.of()),
                new TypeReference<List<Map<String, Object>>>() {}).stream()
                .filter(target -> entryIds.contains(String.valueOf(target.get("entryId"))))
                .toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("请选择待确认的页面");
        var confirmed = objectMapper.convertValue(draft.getOrDefault("targets", List.of()),
                new TypeReference<List<Map<String, Object>>>() {});
        // 多次确认时保留已有结果，同一知识条目只保留一次。
        var merged = new LinkedHashMap<String, Map<String, Object>>();
        confirmed.forEach(target -> merged.put(String.valueOf(target.get("entryId")), target));
        selected.forEach(target -> merged.put(String.valueOf(target.get("entryId")), target));
        draft.put("targets", List.copyOf(merged.values()));
        var now = Instant.now();
        aggregate.update(new PrdSession(current.prdId(), current.systemId(), current.conversationId(), current.workItemId(),
                current.caseId(), current.title(), current.goal(), json(draft), current.missingFields(), current.status(),
                current.createdBy(), current.confirmedBy(), current.confirmedAt(), current.createdAt(), now));
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), current.conversationId(), current.systemId(),
                current.prdId(), "user", "已确认截图对应页面", "[]", "[]", actor.getName(), now));
        return new TargetConfirmationResponse(draft);
    }

    @PostMapping("/prd-sessions/{prdId}/confirm")
    PrdConfirmResponse confirm(@PathVariable String prdId, Authentication actor) {
        var visible = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(visible.systemId(), actor);
        var prepared = transactions.execute(status -> prepareConfirmation(prdId, actor));
        var current = prepared.session();
        if (!prepared.startTemporal()) {
            return new PrdConfirmResponse(prdId, current.workItemId(), current.caseId(), current.status());
        }
        var workItemId = current.workItemId();
        var caseId = current.caseId();
        var now = current.confirmedAt();
        try {
            // Temporal 是外部系统，必须在数据库事务提交后调用。
            var profile = systems.findById(current.systemId()).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
            var agentConfig = readMap(profile.agentConfig());
            try {
                temporal.startCase(new TemporalCasePort.StartCaseCommand(
                        caseId,
                        workItemId,
                        prdId,
                        current.systemId(),
                        profile.repoPath(),
                        readList(profile.allowedPaths()),
                        readList(profile.forbiddenPaths()),
                        readList(profile.testCommands()),
                        configText(agentConfig, "executionProvider", "execution_provider"),
                        configInteger(agentConfig, "claudeMaxTurns", "claude_max_turns"),
                        configInteger(agentConfig, "executionTimeoutSeconds", "execution_timeout_seconds"),
                        prdPayload(current)));
            } catch (WorkflowExecutionAlreadyStarted error) {
                // confirm 幂等：Temporal workflow 已存在说明上一轮启动实际成功，按成功路径收敛。
            }
            append(DomainEventType.TemporalCaseStarted, current.systemId(), caseId, prdId, workItemId, actor.getName(),
                    "TemporalCaseStarted:" + caseId, Map.of("caseId", caseId));
            aggregate.update(new PrdSession(
                    current.prdId(), current.systemId(), current.conversationId(), workItemId, caseId,
                    current.title(), current.goal(), current.draftJson(), current.missingFields(), "waiting_owner_approval",
                    current.createdBy(), actor.getName(), now, current.createdAt(), Instant.now()));
            append(DomainEventType.OwnerApprovalRequested, current.systemId(), caseId, prdId, workItemId, actor.getName(),
                    "OwnerApprovalRequested:" + workItemId, Map.of("caseId", caseId));
        } catch (RuntimeException error) {
            aggregate.update(new PrdSession(
                    current.prdId(), current.systemId(), current.conversationId(), workItemId, caseId,
                    current.title(), current.goal(), current.draftJson(), current.missingFields(), "case_start_failed",
                    current.createdBy(), actor.getName(), now, current.createdAt(), Instant.now()));
            append(DomainEventType.TemporalCaseStartFailed, current.systemId(), caseId, prdId, workItemId, actor.getName(),
                    "TemporalCaseStartFailed:" + caseId, Map.of("caseId", caseId, "reason", error.getMessage()));
            throw new IllegalStateException("Temporal case 启动失败，可重试", error);
        }
        return new PrdConfirmResponse(prdId, workItemId, caseId, "waiting_owner_approval");
    }

    private PreparedConfirmation prepareConfirmation(String prdId, Authentication actor) {
        workItemIds.lockAllocation();
        // 加锁后重新读取，确保并发确认同一个 PRD 时复用首次分配的工作项编号。
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        if ("waiting_owner_approval".equals(current.status()) || "case_starting".equals(current.status())) {
            return new PreparedConfirmation(current, false);
        }
        if (!List.of("waiting_user_confirm", "case_start_failed").contains(current.status())) {
            throw new IllegalStateException("PRD 还不能确认");
        }
        var profile = systems.findById(current.systemId()).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var systemReadiness = readiness.readiness(profile);
        if (!systemReadiness.ready()) {
            throw new ApiException(HttpStatus.CONFLICT, "SYSTEM_NOT_READY", "系统尚未具备真实执行条件", systemReadiness.issues());
        }
        var workItemId = current.workItemId() == null ? workItemIds.nextId() : current.workItemId();
        var caseId = current.caseId() == null ? "case-" + prdId : current.caseId();
        var now = Instant.now();
        var starting = new PrdSession(
                current.prdId(), current.systemId(), current.conversationId(), workItemId, caseId,
                current.title(), current.goal(), current.draftJson(), current.missingFields(), "case_starting",
                current.createdBy(), actor.getName(), now, current.createdAt(), now);
        aggregate.update(starting);
        append(DomainEventType.PRDConfirmed, current.systemId(), caseId, prdId, workItemId, actor.getName(),
                "PRDConfirmed:" + prdId, Map.of("title", current.title()));
        return new PreparedConfirmation(starting, true);
    }

    private TemporalCasePort.PrdPayload prdPayload(PrdSession current) {
        var draft = readMap(current.draftJson());
        return new TemporalCasePort.PrdPayload(
                current.title(),
                current.goal(),
                acceptanceCriteria(draft),
                draft);
    }

    private List<String> acceptanceCriteria(Map<String, Object> draft) {
        var value = draft.get("acceptanceCriteria");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private AnalysisResult analyze(String systemId, List<String> attachmentIds) {
        var observations = new java.util.ArrayList<UiObservation>();
        var failed = false;
        for (var attachmentId : attachmentIds) {
            var attachment = attachments.requireForSystem(attachmentId, systemId);
            try {
                var observation = imageAnalysis.analyze(systemId, attachment, attachments.read(attachment));
                if (observation != null) observations.add(observation);
            } catch (RuntimeException error) {
                // 只记录定位信息和异常类型，图片字节与密钥不得进入日志。
                log.warn("图片分析失败，已降级为文字对话 systemId={} attachmentId={} type={}",
                        systemId, attachmentId, error.getClass().getSimpleName());
                failed = true;
            }
        }
        return new AnalysisResult(observations, failed);
    }

    private void preserveTargets(Map<String, Object> current, Map<String, Object> draft) {
        if (current.containsKey("targets")) draft.put("targets", current.get("targets"));
        if (!draft.containsKey("suspectedTargets") && current.containsKey("suspectedTargets")) {
            draft.put("suspectedTargets", current.get("suspectedTargets"));
        }
    }

    private String assistantMessage(String original, boolean analysisFailed, KnowledgeMatchService.MatchResult match) {
        var message = new StringBuilder(original == null ? "" : original);
        if (analysisFailed) message.append("\n图片分析不可用，已保留图片，不影响文字对话。");
        if (!match.targets().isEmpty()) {
            var target = match.targets().getFirst();
            message.append("\n你反馈的是不是【").append(target.title()).append("】页面？");
            if (!target.apiEndpoints().isEmpty()) message.append("对应接口 ").append(String.join("、", target.apiEndpoints())).append("。");
            message.append("请在右侧确认。");
        } else if (match.knowledgeEmpty()) {
            message.append("\n系统知识库为空，可让管理员运行路由索引。");
        }
        return message.toString();
    }

    private String configText(Map<String, Object> config, String camelName, String snakeName) {
        var value = config.containsKey(camelName) ? config.get(camelName) : config.get(snakeName);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer configInteger(Map<String, Object> config, String camelName, String snakeName) {
        var value = config.containsKey(camelName) ? config.get(camelName) : config.get(snakeName);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
    }

    private void append(DomainEventType type, String systemId, String caseId, String prdId, String workItemId,
                        String actorId, String idempotencyKey, Map<String, Object> payload) {
        events.append(new DomainEventService.AppendEvent(
                type,
                systemId,
                caseId,
                prdId,
                workItemId,
                actorId,
                "control-plane",
                payload,
                prdId,
                null,
                idempotencyKey));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PRD draft 不是合法 JSON", error);
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("missing_fields 不是合法 JSON", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PRD draft 不是合法 JSON", error);
        }
    }

    public record PrdMessageRequest(String prdId, String content, List<String> attachmentIds) {
        public PrdMessageRequest(String prdId, String content) {
            this(prdId, content, List.of());
        }
    }

    public record PrdMessageResponse(String prdId, String conversationId, String status, String assistantMessage, List<String> missingFields, Map<String, Object> draft) {
    }

    public record PrdConfirmResponse(String prdId, String workItemId, String caseId, String lifecycleStatus) {
    }

    private record PreparedConfirmation(PrdSession session, boolean startTemporal) {
    }

    public record TargetConfirmationRequest(List<String> entryIds) {
    }

    public record TargetConfirmationResponse(Map<String, Object> draft) {
    }

    private record AnalysisResult(List<UiObservation> observations, boolean failed) {
    }
}
