package com.asterism.prd;

import com.asterism.attachment.AttachmentService;
import com.asterism.common.ApiException;
import com.asterism.context.ContextRecallQuery;
import com.asterism.context.ContextRecallService;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.knowledge.KnowledgeMatchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PrdConversationService {
    private static final Logger log = LoggerFactory.getLogger(PrdConversationService.class);
    private static final Set<String> EDITABLE_STATUSES = Set.of(
            "waiting_input", "need_clarification", "waiting_user_confirm", "turn_failed", "case_start_failed");

    private final PrdSessionRepository sessions;
    private final ConversationMessageRepository messages;
    private final ProductAgentExecutionRepository executionRepository;
    private final ProductAgentExecutionService executionService;
    private final DomainEventService events;
    private final ObjectMapper objectMapper;
    private final PrdDraftCodec draftCodec;
    private final TransactionOperations transactions;
    private final SystemAccessService access;
    private final ContextRecallService contextRecall;
    private final JdbcAggregateTemplate aggregate;
    private final AttachmentService attachments;

    public PrdConversationService(
            PrdSessionRepository sessions,
            ConversationMessageRepository messages,
            ProductAgentExecutionRepository executionRepository,
            ProductAgentExecutionService executionService,
            DomainEventService events,
            ObjectMapper objectMapper,
            PrdDraftCodec draftCodec,
            TransactionOperations transactions,
            SystemAccessService access,
            ContextRecallService contextRecall,
            JdbcAggregateTemplate aggregate,
            AttachmentService attachments) {
        this.sessions = sessions;
        this.messages = messages;
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.events = events;
        this.objectMapper = objectMapper;
        this.draftCodec = draftCodec;
        this.transactions = transactions;
        this.access = access;
        this.contextRecall = contextRecall;
        this.aggregate = aggregate;
        this.attachments = attachments;
    }

    public PrdMessageResponse message(String systemId, PrdMessageRequest request, Authentication actor) {
        var attachmentIds = request.attachmentIds() == null ? List.<String>of() : request.attachmentIds();
        var content = request.content() == null ? "" : request.content().trim();
        if (attachmentIds.size() > 3) throw new IllegalArgumentException("每条消息最多上传 3 张图片");
        if (attachmentIds.isEmpty() && content.isBlank()) throw new IllegalArgumentException("消息内容和图片不能同时为空");

        final PreparedExecution prepared;
        try {
            prepared = transactions.execute(status -> prepareExecution(
                    systemId, request.prdId(), content, attachmentIds, actor));
        } catch (DataIntegrityViolationException error) {
            // 并发请求最终由 execution 部分唯一索引裁决。
            throw new ApiException(HttpStatus.CONFLICT, "PRD_ASSISTANT_PENDING", "AI 正在分析，请稍候");
        }
        var execution = prepared.execution();
        try {
            execution = executionService.start(execution.executionId());
        } catch (IllegalStateException error) {
            // 创建事实已经提交，启动失败交给 CREATED 恢复任务复用同一 workflowId 重试。
            log.warn("Product Agent 首次启动失败，已返回 executionId 等待自动恢复 executionId={}",
                    execution.executionId());
        }
        return new PrdMessageResponse(execution.executionId(), prepared.session().prdId(),
                prepared.session().conversationId(), execution.status());
    }

    @Transactional
    public Map<String, Object> confirmTargets(String prdId, List<String> entryIds, boolean accepted,
                                              Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
        requireNoActiveExecution(current.prdId());
        var draft = draftCodec.read(current.draftJson());
        var selectedIds = entryIds == null ? List.<String>of() : entryIds;
        var selected = draft.suspectedTargets().stream()
                .filter(target -> selectedIds.contains(target.entryId()))
                .toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("请选择待确认的页面");
        PrdDraft updated;
        if (accepted) {
            // 多次确认时保留已有结果，同一知识条目只保留一次。
            var merged = new LinkedHashMap<String, KnowledgeMatchService.SuspectedTarget>();
            draft.targets().forEach(target -> merged.put(target.entryId(), target));
            selected.forEach(target -> merged.put(target.entryId(), target));
            updated = draft.withTargets(List.copyOf(merged.values()));
        } else {
            // 用户否认的候选直接移除，后续不再重复展示。
            updated = draft.withSuspectedTargets(draft.suspectedTargets().stream()
                    .filter(target -> !selectedIds.contains(target.entryId()))
                    .toList());
        }
        var now = Instant.now();
        aggregate.update(new PrdSession(current.prdId(), current.systemId(), current.conversationId(), current.workItemId(),
                current.caseId(), current.title(), current.goal(), draftCodec.write(updated), current.missingFields(),
                current.status(), current.createdBy(), current.confirmedBy(), current.confirmedAt(), current.createdAt(), now));
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), current.conversationId(), current.systemId(),
                current.prdId(), "user", accepted ? "已确认截图对应页面" : "不是这个页面", "[]", "[]", actor.getName(), now));
        return draftCodec.toMap(updated);
    }

    @Transactional
    public void deleteDraft(String prdId, Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
        if (!actor.getName().equals(current.createdBy())) access.requireOwnerOrAdmin(current.systemId(), actor);
        requireNoActiveExecution(current.prdId());
        // 草稿删除只隐藏业务入口，保留对话和关联工作项用于历史审计。
        sessions.markDeleted(prdId, Instant.now());
        log.info("PRD 草稿已删除 prdId={} actor={}", prdId, actor.getName());
    }

    @Transactional
    public DraftUpdateResponse updateDraft(String prdId, String title, String goal, List<String> acceptanceCriteria,
                                           Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
        requireEditable(current);
        // 手工编辑与活跃 execution 互斥，避免旧执行结果覆盖用户刚保存的内容。
        requireNoActiveExecution(current.prdId());
        var currentDraft = draftCodec.read(current.draftJson());
        var updatedTitle = title == null ? first(currentDraft.title(), current.title()) : title.trim();
        var updatedGoal = goal == null ? first(currentDraft.goal(), current.goal()) : goal.trim();
        var criteria = acceptanceCriteria == null ? null : acceptanceCriteria.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        var messageId = "msg-" + UUID.randomUUID();
        var draft = currentDraft.withManualChanges(updatedTitle, updatedGoal, criteria);
        var userRef = "MSG:" + messageId;
        var manualCitations = new LinkedHashMap<String, List<String>>();
        manualCitations.put("title", List.of(userRef));
        manualCitations.put("goal", List.of(userRef));
        for (var index = 0; index < draft.acceptanceCriteria().size(); index++) {
            manualCitations.put("AC-" + (index + 1), List.of(userRef));
        }
        draft = draft.withCitations(manualCitations, List.of(userRef));
        var missing = ProductAgentExecutionService.missingFields(draft);
        var status = missing.isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var now = Instant.now();
        aggregate.update(new PrdSession(current.prdId(), current.systemId(), current.conversationId(), current.workItemId(),
                current.caseId(), updatedTitle, updatedGoal, draftCodec.write(draft), json(missing), status,
                current.createdBy(), current.confirmedBy(), current.confirmedAt(), current.createdAt(), now));
        aggregate.insert(new ConversationMessage(messageId, current.conversationId(), current.systemId(), current.prdId(),
                "user", manualEditMessage(updatedTitle, updatedGoal, draft.acceptanceCriteria()),
                "[]", "[]", actor.getName(), now));
        append(DomainEventType.PRDUpdated, current.systemId(), current.prdId(), actor.getName(),
                "PRDUpdated:" + current.prdId() + ":manual:" + messageId,
                Map.of("source", "manual_edit", "status", status));
        log.info("PRD draft 已手工更新 prdId={} status={}", current.prdId(), status);
        return new DraftUpdateResponse(updatedTitle, updatedGoal, draftCodec.toMap(draft), List.copyOf(missing), status);
    }

    private String first(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    private String manualEditMessage(String title, String goal, List<String> criteria) {
        return "手工更新 PRD\n标题：" + first(title, "") + "\n目标：" + first(goal, "")
                + "\n验收标准：" + String.join("；", criteria);
    }

    private PreparedExecution prepareExecution(String systemId, String requestedPrdId, String content,
                                               List<String> attachmentIds, Authentication actor) {
        var now = Instant.now();
        var current = requestedPrdId == null ? null : sessions.findById(requestedPrdId).orElse(null);
        if (current == null) {
            access.requireMember(systemId, actor);
        } else {
            access.requireMember(current.systemId(), actor);
            if (!current.systemId().equals(systemId)) {
                throw new ApiException(HttpStatus.CONFLICT, "PRD_SYSTEM_MISMATCH", "PRD 所属系统不能变更");
            }
            requireEditable(current);
            requireNoActiveExecution(current.prdId());
        }
        var prdId = current == null ? "prd-" + UUID.randomUUID() : current.prdId();
        var conversationId = current == null ? "conv-" + prdId : current.conversationId();
        var history = current == null ? List.<ConversationMessage>of()
                : messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        attachmentIds.forEach(attachmentId -> attachments.requireForSystem(attachmentId, systemId));
        var session = current == null
                ? new PrdSession(prdId, systemId, conversationId, null, null, null, content, "{}", "[]",
                "need_clarification", actor.getName(), null, null, now, now)
                : current;
        if (current == null) aggregate.insert(session);
        var userMessage = new ConversationMessage("msg-" + UUID.randomUUID(), conversationId, systemId, prdId,
                "user", content, json(attachmentIds), "[]", actor.getName(), now);
        aggregate.insert(userMessage);
        var currentDraft = draftCodec.read(session.draftJson());
        var targetRefs = currentDraft.targets().stream().map(KnowledgeMatchService.SuspectedTarget::entryId).toList();
        var bundle = contextRecall.recall(new ContextRecallQuery(
                systemId, prdId, "product", content, userMessage.messageId(), draftCodec.toMap(currentDraft),
                targetRefs, history, actor.getName()));
        var executionId = "prd-exec-" + UUID.randomUUID();
        var execution = new ProductAgentExecution(
                executionId, prdId, ProductAgentExecutionStatus.CREATED, "product-agent-" + executionId,
                userMessage.messageId(), bundle.bundleId(), "CREATED", 0, null,
                null, null, null, null, now, now);
        append(DomainEventType.UserMessageReceived, systemId, prdId, actor.getName(),
                "UserMessageReceived:" + prdId + ":" + executionId,
                Map.of("content", content, "executionId", executionId));
        var createdEventId = "evt-product-agent-created-" + executionId;
        events.append(new DomainEventService.AppendEvent(
                DomainEventType.ProductAgentExecutionCreated, systemId, null, prdId, null,
                actor.getName(), "control-plane",
                Map.of(
                        "executionId", execution.executionId(),
                        "workflowId", execution.workflowId(),
                        "inputMessageId", execution.inputMessageId(),
                        "contextBundleId", execution.contextBundleId(),
                        "status", execution.status().name()),
                execution.executionId(), userMessage.messageId(),
                "ProductAgentExecutionCreated:" + executionId, createdEventId));
        // execution 是 Created 事件的查询投影，二者与输入消息、上下文在同一事务提交。
        aggregate.insert(execution);
        return new PreparedExecution(session, execution);
    }

    private void requireEditable(PrdSession session) {
        // 工作项 ID 可能由导入流程预分配，编辑权限只看 PRD 生命周期。
        if (!EDITABLE_STATUSES.contains(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PRD_DRAFT_NOT_EDITABLE", "当前状态不能编辑 PRD");
        }
    }

    private void requireNoActiveExecution(String prdId) {
        if (executionRepository.findActiveByPrdId(prdId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRD_ASSISTANT_PENDING", "AI 正在分析，请稍候");
        }
    }

    private void append(DomainEventType type, String systemId, String prdId, String actorId,
                        String idempotencyKey, Map<String, Object> payload) {
        events.append(new DomainEventService.AppendEvent(type, systemId, null, prdId, null, actorId,
                "control-plane", payload, prdId, null, idempotencyKey));
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

    public record PrdMessageResponse(String executionId, String prdId, String conversationId,
                                     ProductAgentExecutionStatus status) {
    }

    public record DraftUpdateResponse(String title, String goal, Map<String, Object> draft,
                                      List<String> missingFields, String status) {
    }

    private record PreparedExecution(PrdSession session, ProductAgentExecution execution) {
    }
}
