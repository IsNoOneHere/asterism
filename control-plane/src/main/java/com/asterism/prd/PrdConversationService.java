package com.asterism.prd;

import com.asterism.attachment.Attachment;
import com.asterism.attachment.AttachmentService;
import com.asterism.common.ApiException;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.identity.SystemAccessService;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.vision.ImageAnalysisService;
import com.asterism.vision.UiObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PrdConversationService {
    private static final Logger log = LoggerFactory.getLogger(PrdConversationService.class);
    private static final String AI_UNAVAILABLE = "AI 暂时不可用，请重试";
    static final String PENDING_SENDER = "assistant_pending";
    static final long PENDING_TIMEOUT_SECONDS = 120;

    private final PrdSessionRepository sessions;
    private final ConversationMessageRepository messages;
    private final ProductAgentPort productAgent;
    private final DomainEventService events;
    private final ObjectMapper objectMapper;
    private final PrdDraftCodec draftCodec;
    private final TransactionOperations transactions;
    private final SystemAccessService access;
    private final MemoryItemRepository memories;
    private final JdbcAggregateTemplate aggregate;
    private final AttachmentService attachments;
    private final ImageAnalysisService imageAnalysis;
    private final KnowledgeMatchService knowledge;
    private final TaskExecutor taskExecutor;

    public PrdConversationService(
            PrdSessionRepository sessions,
            ConversationMessageRepository messages,
            ProductAgentPort productAgent,
            DomainEventService events,
            ObjectMapper objectMapper,
            PrdDraftCodec draftCodec,
            TransactionOperations transactions,
            SystemAccessService access,
            MemoryItemRepository memories,
            JdbcAggregateTemplate aggregate,
            AttachmentService attachments,
            ImageAnalysisService imageAnalysis,
            KnowledgeMatchService knowledge,
            TaskExecutor taskExecutor) {
        this.sessions = sessions;
        this.messages = messages;
        this.productAgent = productAgent;
        this.events = events;
        this.objectMapper = objectMapper;
        this.draftCodec = draftCodec;
        this.transactions = transactions;
        this.access = access;
        this.memories = memories;
        this.aggregate = aggregate;
        this.attachments = attachments;
        this.imageAnalysis = imageAnalysis;
        this.knowledge = knowledge;
        this.taskExecutor = taskExecutor;
    }

    public PrdMessageResponse message(String systemId, PrdMessageRequest request, Authentication actor) {
        var attachmentIds = request.attachmentIds() == null ? List.<String>of() : request.attachmentIds();
        var content = request.content() == null ? "" : request.content().trim();
        if (attachmentIds.size() > 3) throw new IllegalArgumentException("每条消息最多上传 3 张图片");
        if (attachmentIds.isEmpty() && content.isBlank()) throw new IllegalArgumentException("消息内容和图片不能同时为空");

        final PreparedTurn turn;
        try {
            turn = transactions.execute(status -> beginTurn(systemId, request.prdId(), content, attachmentIds, actor));
        } catch (DataIntegrityViolationException error) {
            // 并发请求最终由数据库唯一索引裁决。
            throw new ApiException(HttpStatus.CONFLICT, "PRD_ASSISTANT_PENDING", "AI 正在分析，请稍候");
        }
        var response = new PrdMessageResponse(turn.session().prdId(), turn.session().conversationId(),
                turn.session().status(), null, turn.currentMissing(), draftCodec.toMap(turn.currentDraft()), true);
        try {
            taskExecutor.execute(() -> executeTurn(turn));
        } catch (RuntimeException error) {
            log.warn("PRD 后台执行器拒绝回合 prdId={} type={}", turn.session().prdId(), error.getClass().getSimpleName());
            transactions.executeWithoutResult(status -> failTurn(turn));
            return new PrdMessageResponse(response.prdId(), response.conversationId(), response.status(), AI_UNAVAILABLE,
                    response.missingFields(), response.draft(), false);
        }
        return response;
    }

    @Transactional
    public Map<String, Object> confirmTargets(String prdId, List<String> entryIds, boolean accepted,
                                              Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
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
    public DraftUpdateResponse updateDraft(String prdId, String title, String goal, List<String> acceptanceCriteria,
                                           Authentication actor) {
        var current = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(current.systemId(), actor);
        if (!List.of("need_clarification", "waiting_user_confirm").contains(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PRD_DRAFT_NOT_EDITABLE", "当前状态不能编辑 PRD");
        }
        // 手工编辑与后台回合互斥，避免旧快照覆盖用户刚保存的内容。
        if (messages.findFirstByConversationIdAndSenderTypeOrderByCreatedAtAsc(
                current.conversationId(), PENDING_SENDER).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRD_ASSISTANT_PENDING", "AI 正在分析，请稍候");
        }
        var currentDraft = draftCodec.read(current.draftJson());
        var updatedTitle = title == null ? first(currentDraft.title(), current.title()) : title.trim();
        var updatedGoal = goal == null ? first(currentDraft.goal(), current.goal()) : goal.trim();
        var criteria = acceptanceCriteria == null ? null : acceptanceCriteria.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        var draft = currentDraft.withManualChanges(updatedTitle, updatedGoal, criteria);
        var missing = new ArrayList<>(readList(current.missingFields()));
        missing.removeIf(field -> List.of("title", "goal", "acceptanceCriteria", "acceptance_criteria").contains(field));
        if (updatedTitle == null || updatedTitle.isBlank()) missing.add("title");
        if (updatedGoal == null || updatedGoal.isBlank()) missing.add("goal");
        if (draft.acceptanceCriteria().isEmpty()) missing.add("acceptance_criteria");
        var status = missing.isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var now = Instant.now();
        var messageId = "msg-" + UUID.randomUUID();
        aggregate.update(new PrdSession(current.prdId(), current.systemId(), current.conversationId(), current.workItemId(),
                current.caseId(), updatedTitle, updatedGoal, draftCodec.write(draft), json(missing), status,
                current.createdBy(), current.confirmedBy(), current.confirmedAt(), current.createdAt(), now));
        aggregate.insert(new ConversationMessage(messageId, current.conversationId(), current.systemId(), current.prdId(),
                "system", "用户手动更新了验收标准", "[]", "[]", actor.getName(), now));
        append(DomainEventType.PRDUpdated, current.systemId(), current.prdId(), actor.getName(),
                "PRDUpdated:" + current.prdId() + ":manual:" + messageId,
                Map.of("source", "manual_edit", "status", status));
        log.info("PRD draft 已手工更新 prdId={} status={}", current.prdId(), status);
        return new DraftUpdateResponse(updatedTitle, updatedGoal, draftCodec.toMap(draft), List.copyOf(missing), status);
    }

    private String first(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }

    private void executeTurn(PreparedTurn turn) {
        try {
            // HTTP/LLM 与图片字节处理必须处于数据库事务之外。
            var result = processTurn(turn);
            transactions.executeWithoutResult(status -> completeTurn(turn, result));
        } catch (RuntimeException error) {
            log.warn("PRD AI 回合失败，已保留用户消息 prdId={} type={}",
                    turn.session().prdId(), error.getClass().getSimpleName());
            transactions.executeWithoutResult(status -> failTurn(turn));
        }
    }

    private PreparedTurn beginTurn(String systemId, String requestedPrdId, String content, List<String> attachmentIds,
                                   Authentication actor) {
        var now = Instant.now();
        var current = requestedPrdId == null ? null : sessions.findById(requestedPrdId).orElse(null);
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
        var pending = messages.findFirstByConversationIdAndSenderTypeOrderByCreatedAtAsc(conversationId, PENDING_SENDER);
        if (pending.isPresent() && pending.get().createdAt().isAfter(now.minusSeconds(PENDING_TIMEOUT_SECONDS))) {
            throw new ApiException(HttpStatus.CONFLICT, "PRD_ASSISTANT_PENDING", "AI 正在分析，请稍候");
        }
        pending.ifPresent(value -> messages.completePending(value.messageId(), AI_UNAVAILABLE));
        var turn = messages.countByConversationIdAndSenderType(conversationId, "user") + 1;
        var history = current == null ? List.<ConversationMessage>of()
                : messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        var approvedMemories = memories.findBySystemIdAndStatus(systemId, "approved").stream()
                .map(memory -> memory.content())
                .toList();
        var validatedAttachments = attachmentIds.stream()
                .map(attachmentId -> attachments.requireForSystem(attachmentId, systemId))
                .toList();
        var session = current == null
                ? new PrdSession(prdId, systemId, conversationId, null, null, null, content, "{}", "[]",
                "need_clarification", actor.getName(), null, null, now, now)
                : current;
        if (current == null) aggregate.insert(session);
        var userMessage = new ConversationMessage("msg-" + UUID.randomUUID(), conversationId, systemId, prdId,
                "user", content, json(attachmentIds), "[]", actor.getName(), now);
        aggregate.insert(userMessage);
        var pendingMessage = new ConversationMessage("msg-" + UUID.randomUUID(), conversationId, systemId, prdId,
                PENDING_SENDER, "", "[]", "[]", "product-agent", now.plusNanos(1_000));
        aggregate.insert(pendingMessage);
        append(DomainEventType.UserMessageReceived, systemId, prdId, actor.getName(),
                "UserMessageReceived:" + prdId + ":" + turn, Map.of("content", content, "turn", turn));
        return new PreparedTurn(session, current == null, userMessage, pendingMessage, turn, history, approvedMemories,
                validatedAttachments, draftCodec.read(session.draftJson()), readList(session.missingFields()), actor.getName());
    }

    private ProcessedTurn processTurn(PreparedTurn turn) {
        var analysis = analyze(turn.session().systemId(), turn.attachments());
        var agentContent = turn.userMessage().content() + (analysis.observations().isEmpty() ? "" : "\n截图观察："
                + analysis.observations().stream().map(UiObservation::contextText).collect(Collectors.joining("\n")));
        var result = productAgent.updateDraft(turn.session().systemId(), agentContent, draftCodec.toMap(turn.currentDraft()),
                turn.currentMissing(), turn.history(), turn.approvedMemories());
        var draft = draftCodec.fromMap(result.draft()).withTitle(result.title()).preserveTargets(turn.currentDraft());
        var anchors = analysis.observations().stream().flatMap(observation -> observation.anchors().stream()).toList();
        var match = anchors.isEmpty() ? new KnowledgeMatchService.MatchResult(List.of(), false)
                : knowledge.match(turn.session().systemId(), anchors);
        if (!match.targets().isEmpty()) draft = draft.withSuspectedTargets(match.targets());
        return new ProcessedTurn(result, draft, analysis,
                assistantMessage(result.assistantMessage(), analysis.failed(), match));
    }

    private void completeTurn(PreparedTurn turn, ProcessedTurn processed) {
        var now = Instant.now();
        var result = processed.result();
        if (messages.completePending(turn.pendingMessage().messageId(), processed.assistantMessage()) == 0) return;
        var status = result.missingFields().isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var title = turn.newSession() || turn.session().title() == null ? processed.draft().title() : turn.session().title();
        var goal = turn.newSession() ? processed.draft().goal() : turn.session().goal();
        var session = new PrdSession(turn.session().prdId(), turn.session().systemId(), turn.session().conversationId(),
                turn.session().workItemId(), turn.session().caseId(), title, goal, draftCodec.write(processed.draft()),
                json(result.missingFields()), status, turn.session().createdBy(), turn.session().confirmedBy(),
                turn.session().confirmedAt(), turn.session().createdAt(), now);
        aggregate.update(session);
        aggregate.update(new ConversationMessage(turn.userMessage().messageId(), turn.userMessage().conversationId(),
                turn.userMessage().systemId(), turn.userMessage().prdId(), turn.userMessage().senderType(),
                turn.userMessage().content(), turn.userMessage().attachmentIds(), json(processed.analysis().observations()),
                turn.userMessage().createdBy(), turn.userMessage().createdAt()));
        append(DomainEventType.PRDUpdated, session.systemId(), session.prdId(), turn.actorId(),
                "PRDUpdated:" + session.prdId() + ":" + turn.turn(),
                Map.of("title", title, "status", status, "turn", turn.turn()));
        if (!result.missingFields().isEmpty()) {
            append(DomainEventType.ClarificationRequested, session.systemId(), session.prdId(), "product-agent",
                    "ClarificationRequested:" + session.prdId() + ":" + turn.turn(),
                    Map.of("missingFields", result.missingFields()));
        }
    }

    private void failTurn(PreparedTurn turn) {
        messages.completePending(turn.pendingMessage().messageId(), AI_UNAVAILABLE);
    }

    private AnalysisResult analyze(String systemId, List<Attachment> turnAttachments) {
        var observations = new ArrayList<UiObservation>();
        var failed = false;
        for (var attachment : turnAttachments) {
            try {
                var observation = imageAnalysis.analyze(systemId, attachment, attachments.read(attachment));
                if (observation != null) observations.add(observation);
            } catch (RuntimeException error) {
                // 只记录定位信息和异常类型，图片字节与密钥不得进入日志。
                log.warn("图片分析失败，已降级为文字对话 systemId={} attachmentId={} type={}",
                        systemId, attachment.attachmentId(), error.getClass().getSimpleName());
                failed = true;
            }
        }
        return new AnalysisResult(observations, failed);
    }

    private String assistantMessage(String original, boolean analysisFailed, KnowledgeMatchService.MatchResult match) {
        var message = new StringBuilder(original == null ? "" : original);
        if (analysisFailed) message.append("\n图片分析不可用，已保留图片，不影响文字对话。");
        if (!match.targets().isEmpty()) {
            var target = match.targets().getFirst();
            message.append("\n你反馈的是不是【").append(target.title()).append("】页面？");
            if (!target.apiEndpoints().isEmpty()) message.append("对应接口 ").append(String.join("、", target.apiEndpoints())).append("。");
            message.append("请确认。");
        } else if (match.knowledgeEmpty()) {
            message.append("\n系统知识库为空，可让管理员运行路由索引。");
        }
        return message.toString();
    }

    private void append(DomainEventType type, String systemId, String prdId, String actorId,
                        String idempotencyKey, Map<String, Object> payload) {
        events.append(new DomainEventService.AppendEvent(type, systemId, null, prdId, null, actorId,
                "control-plane", payload, prdId, null, idempotencyKey));
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
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

    public record PrdMessageResponse(String prdId, String conversationId, String status, String assistantMessage,
                                     List<String> missingFields, Map<String, Object> draft, boolean assistantPending) {
    }

    public record DraftUpdateResponse(String title, String goal, Map<String, Object> draft,
                                      List<String> missingFields, String status) {
    }

    private record PreparedTurn(PrdSession session, boolean newSession, ConversationMessage userMessage,
                                ConversationMessage pendingMessage, long turn, List<ConversationMessage> history,
                                List<String> approvedMemories,
                                List<Attachment> attachments, PrdDraft currentDraft,
                                List<String> currentMissing, String actorId) {
    }

    private record ProcessedTurn(ProductAgentPort.DraftResult result, PrdDraft draft,
                                 AnalysisResult analysis, String assistantMessage) {
    }

    private record AnalysisResult(List<UiObservation> observations, boolean failed) {
    }
}
