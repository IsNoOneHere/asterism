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
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
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

    private final PrdSessionRepository sessions;
    private final ConversationMessageRepository messages;
    private final ProductAgentPort productAgent;
    private final DomainEventService events;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactions;
    private final SystemAccessService access;
    private final MemoryItemRepository memories;
    private final JdbcAggregateTemplate aggregate;
    private final AttachmentService attachments;
    private final ImageAnalysisService imageAnalysis;
    private final KnowledgeMatchService knowledge;

    public PrdConversationService(
            PrdSessionRepository sessions,
            ConversationMessageRepository messages,
            ProductAgentPort productAgent,
            DomainEventService events,
            ObjectMapper objectMapper,
            TransactionOperations transactions,
            SystemAccessService access,
            MemoryItemRepository memories,
            JdbcAggregateTemplate aggregate,
            AttachmentService attachments,
            ImageAnalysisService imageAnalysis,
            KnowledgeMatchService knowledge) {
        this.sessions = sessions;
        this.messages = messages;
        this.productAgent = productAgent;
        this.events = events;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.access = access;
        this.memories = memories;
        this.aggregate = aggregate;
        this.attachments = attachments;
        this.imageAnalysis = imageAnalysis;
        this.knowledge = knowledge;
    }

    public PrdMessageResponse message(String systemId, PrdMessageRequest request, Authentication actor) {
        var attachmentIds = request.attachmentIds() == null ? List.<String>of() : request.attachmentIds();
        var content = request.content() == null ? "" : request.content().trim();
        if (attachmentIds.size() > 3) throw new IllegalArgumentException("每条消息最多上传 3 张图片");
        if (attachmentIds.isEmpty() && content.isBlank()) throw new IllegalArgumentException("消息内容和图片不能同时为空");

        var turn = transactions.execute(status -> beginTurn(systemId, request.prdId(), content, attachmentIds, actor));
        try {
            // HTTP/LLM 与图片字节处理必须处于数据库事务之外。
            var result = processTurn(turn);
            return transactions.execute(status -> completeTurn(turn, result));
        } catch (RuntimeException error) {
            log.warn("PRD AI 回合失败，已保留用户消息 prdId={} type={}",
                    turn.session().prdId(), error.getClass().getSimpleName());
            return transactions.execute(status -> failTurn(turn));
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
        var turn = messages.countByConversationIdAndSenderType(conversationId, "user") + 1;
        var history = current == null ? List.<ConversationMessage>of()
                : messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        var approvedMemories = memories.findBySystemIdAndStatus(systemId, "approved").stream()
                .limit(5)
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
        append(DomainEventType.UserMessageReceived, systemId, prdId, actor.getName(),
                "UserMessageReceived:" + prdId + ":" + turn, Map.of("content", content, "turn", turn));
        return new PreparedTurn(session, current == null, userMessage, turn, history, approvedMemories,
                validatedAttachments, readMap(session.draftJson()), readList(session.missingFields()), actor.getName());
    }

    private ProcessedTurn processTurn(PreparedTurn turn) {
        var analysis = analyze(turn.session().systemId(), turn.attachments());
        var agentContent = turn.userMessage().content() + (analysis.observations().isEmpty() ? "" : "\n截图观察："
                + analysis.observations().stream().map(UiObservation::contextText).collect(Collectors.joining("\n")));
        var result = productAgent.updateDraft(turn.session().systemId(), agentContent, turn.currentDraft(),
                turn.currentMissing(), turn.history(), turn.approvedMemories());
        var draft = new LinkedHashMap<>(result.draft());
        preserveTargets(turn.currentDraft(), draft);
        var anchors = analysis.observations().stream().flatMap(observation -> observation.anchors().stream()).toList();
        var match = anchors.isEmpty() ? new KnowledgeMatchService.MatchResult(List.of(), false)
                : knowledge.match(turn.session().systemId(), anchors);
        if (!match.targets().isEmpty()) draft.put("suspectedTargets", match.targets());
        return new ProcessedTurn(result, draft, analysis, match, agentContent,
                assistantMessage(result.assistantMessage(), analysis.failed(), match));
    }

    private PrdMessageResponse completeTurn(PreparedTurn turn, ProcessedTurn processed) {
        var now = Instant.now();
        var result = processed.result();
        var status = result.missingFields().isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var title = turn.newSession() || turn.session().title() == null ? result.title() : turn.session().title();
        var goal = turn.newSession() ? processed.agentContent() : turn.session().goal();
        var session = new PrdSession(turn.session().prdId(), turn.session().systemId(), turn.session().conversationId(),
                turn.session().workItemId(), turn.session().caseId(), title, goal, json(processed.draft()),
                json(result.missingFields()), status, turn.session().createdBy(), turn.session().confirmedBy(),
                turn.session().confirmedAt(), turn.session().createdAt(), now);
        aggregate.update(session);
        aggregate.update(new ConversationMessage(turn.userMessage().messageId(), turn.userMessage().conversationId(),
                turn.userMessage().systemId(), turn.userMessage().prdId(), turn.userMessage().senderType(),
                turn.userMessage().content(), turn.userMessage().attachmentIds(), json(processed.analysis().observations()),
                turn.userMessage().createdBy(), turn.userMessage().createdAt()));
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), session.conversationId(), session.systemId(),
                session.prdId(), "assistant", processed.assistantMessage(), "[]", "[]", "product-agent", now));
        append(DomainEventType.PRDUpdated, session.systemId(), session.prdId(), turn.actorId(),
                "PRDUpdated:" + session.prdId() + ":" + turn.turn(),
                Map.of("title", title, "status", status, "turn", turn.turn()));
        if (!result.missingFields().isEmpty()) {
            append(DomainEventType.ClarificationRequested, session.systemId(), session.prdId(), "product-agent",
                    "ClarificationRequested:" + session.prdId() + ":" + turn.turn(),
                    Map.of("missingFields", result.missingFields()));
        }
        return response(session, processed.assistantMessage(), result.missingFields(), processed.draft());
    }

    private PrdMessageResponse failTurn(PreparedTurn turn) {
        var session = sessions.findById(turn.session().prdId()).orElse(turn.session());
        aggregate.insert(new ConversationMessage("msg-" + UUID.randomUUID(), session.conversationId(), session.systemId(),
                session.prdId(), "assistant", AI_UNAVAILABLE, "[]", "[]", "product-agent", Instant.now()));
        return response(session, AI_UNAVAILABLE, readList(session.missingFields()), readMap(session.draftJson()));
    }

    private PrdMessageResponse response(PrdSession session, String assistantMessage, List<String> missingFields,
                                        Map<String, Object> draft) {
        return new PrdMessageResponse(session.prdId(), session.conversationId(), session.status(), assistantMessage,
                missingFields, draft);
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

    private void append(DomainEventType type, String systemId, String prdId, String actorId,
                        String idempotencyKey, Map<String, Object> payload) {
        events.append(new DomainEventService.AppendEvent(type, systemId, null, prdId, null, actorId,
                "control-plane", payload, prdId, null, idempotencyKey));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PRD draft 不是合法 JSON", error);
        }
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
                                     List<String> missingFields, Map<String, Object> draft) {
    }

    private record PreparedTurn(PrdSession session, boolean newSession, ConversationMessage userMessage, long turn,
                                List<ConversationMessage> history, List<String> approvedMemories,
                                List<Attachment> attachments, Map<String, Object> currentDraft,
                                List<String> currentMissing, String actorId) {
    }

    private record ProcessedTurn(ProductAgentPort.DraftResult result, Map<String, Object> draft,
                                 AnalysisResult analysis, KnowledgeMatchService.MatchResult match,
                                 String agentContent, String assistantMessage) {
    }

    private record AnalysisResult(List<UiObservation> observations, boolean failed) {
    }
}
