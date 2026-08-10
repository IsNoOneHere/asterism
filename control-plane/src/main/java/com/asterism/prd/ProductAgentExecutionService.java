package com.asterism.prd;

import com.asterism.context.ContextBundleStore;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.knowledge.KnowledgeMatchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductAgentExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ProductAgentExecutionService.class);

    private final ProductAgentExecutionRepository executions;
    private final PrdSessionRepository sessions;
    private final ConversationMessageRepository messages;
    private final ContextBundleStore bundles;
    private final ProductAgentExecutionPort executionPort;
    private final ObjectMapper objectMapper;
    private final PrdDraftCodec draftCodec;
    private final PrdCitationService citations;
    private final JdbcAggregateTemplate aggregate;
    private final KnowledgeMatchService knowledge;
    private final DomainEventService events;
    private final TransactionOperations transactions;

    public ProductAgentExecutionService(
            ProductAgentExecutionRepository executions,
            PrdSessionRepository sessions,
            ConversationMessageRepository messages,
            ContextBundleStore bundles,
            ProductAgentExecutionPort executionPort,
            ObjectMapper objectMapper,
            PrdDraftCodec draftCodec,
            PrdCitationService citations,
            JdbcAggregateTemplate aggregate,
            KnowledgeMatchService knowledge,
            DomainEventService events,
            TransactionOperations transactions) {
        this.executions = executions;
        this.sessions = sessions;
        this.messages = messages;
        this.bundles = bundles;
        this.executionPort = executionPort;
        this.objectMapper = objectMapper;
        this.draftCodec = draftCodec;
        this.citations = citations;
        this.aggregate = aggregate;
        this.knowledge = knowledge;
        this.events = events;
        this.transactions = transactions;
    }

    public ProductAgentExecution start(String executionId) {
        var command = transactions.execute(status -> prepareStart(executionId));
        try {
            // Temporal 调用必须发生在创建 execution 的数据库事务提交之后。
            executionPort.start(command);
        } catch (WorkflowExecutionAlreadyStarted error) {
            // 相同 Workflow ID 已存在即视为上一次启动成功，重试不创建第二个 workflow。
            log.info("Product Agent workflow 已存在，按幂等成功处理 executionId={} workflowId={}",
                    command.executionId(), command.workflowId());
        } catch (RuntimeException error) {
            transactions.executeWithoutResult(status -> executions.recordStartFailure(
                    executionId, "TEMPORAL_START_FAILED", Instant.now()));
            log.warn("Product Agent workflow 启动失败，可复用原 execution 重试 executionId={} type={}",
                    executionId, error.getClass().getSimpleName());
            throw new IllegalStateException("Product Agent workflow 启动失败，可重试", error);
        }
        return require(executionId);
    }

    @Transactional
    public ProductAgentExecution apply(String executionId, ProductAgentExecutionEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("Product Agent execution eventId 不能为空");
        }
        var current = require(executionId);
        var lifecycleEvent = lifecycleEvent(current, event);
        if (events.exists(lifecycleEvent.idempotencyKey())) {
            log.info("Product Agent execution 事件已处理 executionId={} eventId={}", executionId, event.eventId());
            return current;
        }
        // 先记录 Worker 原始生命周期事实，再在同一事务内更新查询投影。
        events.append(lifecycleEvent);
        if (current.status().terminal()) return current;
        var now = Instant.now();
        return switch (event.eventType()) {
            case Started -> started(current, event, now);
            case Heartbeat -> heartbeat(current, event, now);
            case Completed -> completed(current, event, now);
            case Failed -> failed(current, event, now);
            case Cancelled -> cancelled(current, event, now);
        };
    }

    public ProductAgentExecution require(String executionId) {
        return executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Product Agent execution 不存在"));
    }

    public ProductAgentExecutionView latestView(String prdId) {
        return executions.findLatestByPrdId(prdId).map(ProductAgentExecutionView::from).orElse(null);
    }

    private ProductAgentExecutionPort.StartExecutionCommand prepareStart(String executionId) {
        var execution = require(executionId);
        if (execution.status() != ProductAgentExecutionStatus.CREATED) {
            throw new IllegalStateException("只有 CREATED execution 可以启动");
        }
        var session = sessions.findById(execution.prdId())
                .orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        var input = messages.findById(execution.inputMessageId())
                .orElseThrow(() -> new IllegalArgumentException("execution 输入消息不存在"));
        var bundle = bundles.find(execution.contextBundleId())
                .orElseThrow(() -> new IllegalArgumentException("execution 上下文不存在"));
        var draft = draftCodec.read(session.draftJson());
        var attempt = execution.attempt() + 1;
        if (executions.recordStartAttempt(executionId, Instant.now()) != 1) {
            throw new IllegalStateException("Product Agent execution 已不再可启动");
        }
        var history = messages.findByConversationIdOrderByCreatedAtAsc(session.conversationId()).stream()
                .filter(message -> "user".equals(message.senderType()))
                .filter(message -> !message.messageId().equals(input.messageId()))
                .toList();
        var productContext = bundle.items().stream()
                .filter(item -> !"system_knowledge".equals(item.type()))
                .toList();
        return new ProductAgentExecutionPort.StartExecutionCommand(
                execution.executionId(), execution.workflowId(), session.systemId(), session.prdId(),
                session.conversationId(), input.messageId(), bundle.bundleId(), input.content(),
                readList(input.attachmentIds()), draft.productContent(), missingFields(draft), history,
                productContext, attempt);
    }

    private ProductAgentExecution started(ProductAgentExecution current, ProductAgentExecutionEvent event, Instant now) {
        if (current.status() == ProductAgentExecutionStatus.RUNNING) return current;
        executions.markStarted(current.executionId(), stage(event, "RUNNING"), attempt(current, event), now);
        return require(current.executionId());
    }

    private ProductAgentExecution heartbeat(ProductAgentExecution current, ProductAgentExecutionEvent event, Instant now) {
        if (current.status() != ProductAgentExecutionStatus.RUNNING) return current;
        executions.heartbeat(current.executionId(), stage(event, current.stage()), attempt(current, event), now);
        return require(current.executionId());
    }

    private ProductAgentExecution completed(ProductAgentExecution current, ProductAgentExecutionEvent event, Instant now) {
        if (!current.status().active()) return current;
        if (event.result() == null) throw new IllegalArgumentException("Completed event 缺少 DraftResult");
        if (executions.markCompleted(
                current.executionId(), stage(event, "COMPLETED"), attempt(current, event), now) != 1) {
            return require(current.executionId());
        }

        var session = sessions.findById(current.prdId())
                .orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        var input = messages.findById(current.inputMessageId())
                .orElseThrow(() -> new IllegalArgumentException("execution 输入消息不存在"));
        var bundle = bundles.find(current.contextBundleId())
                .orElseThrow(() -> new IllegalArgumentException("execution 上下文不存在"));
        var previous = draftCodec.read(session.draftJson());
        var draft = previous.apply(event.result().patch());
        var citationResult = citations.validateAndMerge(bundle, previous, draft, event.result());
        draft = draft.withCitations(citationResult.citations(), citationResult.usedRefs());
        var anchors = event.observations().stream().flatMap(observation -> observation.anchors().stream()).toList();
        if (!anchors.isEmpty()) {
            var match = knowledge.match(session.systemId(), anchors);
            if (!match.targets().isEmpty()) draft = draft.withSuspectedTargets(match.targets());
        }

        var missing = missingFields(draft);
        var prdStatus = missing.isEmpty() ? "waiting_user_confirm" : "need_clarification";
        var resultMessageId = "msg-" + UUID.randomUUID();
        aggregate.update(new PrdSession(
                session.prdId(), session.systemId(), session.conversationId(), session.workItemId(), session.caseId(),
                draft.title(), draft.goal(), draftCodec.write(draft), json(missing), prdStatus,
                session.createdBy(), session.confirmedBy(), session.confirmedAt(), session.createdAt(), now));
        aggregate.update(new ConversationMessage(
                input.messageId(), input.conversationId(), input.systemId(), input.prdId(), input.senderType(),
                input.content(), input.attachmentIds(), json(event.observations()), input.contextBundleId(),
                input.usedContextRefs(), input.citationsJson(), input.createdBy(), input.createdAt()));
        aggregate.insert(new ConversationMessage(
                resultMessageId, session.conversationId(), session.systemId(), session.prdId(), "assistant",
                assistantMessage(event.result().assistantMessage(), event.imageAnalysisFailed()), "[]", "[]",
                bundle.bundleId(), json(citationResult.usedRefs()), json(citationResult.citations()),
                "product-agent", now));
        if (executions.attachResultMessage(current.executionId(), resultMessageId, now) != 1) {
            throw new IllegalStateException("Product Agent resultMessageId 写入失败");
        }
        appendCompletionEvents(current, session, draft, missing, prdStatus, citationResult.usedRefs());
        log.info("Product Agent execution 投影完成 executionId={} prdId={} status={}",
                current.executionId(), current.prdId(), prdStatus);
        return require(current.executionId());
    }

    private ProductAgentExecution failed(ProductAgentExecution current, ProductAgentExecutionEvent event, Instant now) {
        executions.markFailed(current.executionId(), stage(event, "FAILED"),
                text(event.failureCode(), "PRODUCT_AGENT_FAILED"), now);
        log.warn("Product Agent execution 失败 executionId={} code={}",
                current.executionId(), text(event.failureCode(), "PRODUCT_AGENT_FAILED"));
        return require(current.executionId());
    }

    private ProductAgentExecution cancelled(ProductAgentExecution current, ProductAgentExecutionEvent event, Instant now) {
        executions.markCancelled(current.executionId(), stage(event, "CANCELLED"), event.failureCode(), now);
        log.info("Product Agent execution 已取消 executionId={}", current.executionId());
        return require(current.executionId());
    }

    private DomainEventService.AppendEvent lifecycleEvent(
            ProductAgentExecution execution, ProductAgentExecutionEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("executionId", execution.executionId());
        payload.put("workflowId", execution.workflowId());
        payload.put("eventId", event.eventId());
        payload.put("eventType", event.eventType().name());
        if (event.stage() != null) payload.put("stage", event.stage());
        if (event.attempt() != null) payload.put("attempt", event.attempt());
        if (event.failureCode() != null) payload.put("failureCode", event.failureCode());
        if (event.idempotencyKey() != null && !event.idempotencyKey().isBlank()) {
            payload.put("workerIdempotencyKey", event.idempotencyKey());
        }
        if (event.result() != null) payload.put("result", event.result());
        if (event.generatedArtifactCandidate() != null) {
            // Worker 只提交候选事实；这里不创建 Artifact，也不推进 Head。
            payload.put("generatedArtifactCandidate", event.generatedArtifactCandidate());
        }
        if (!event.observations().isEmpty()) payload.put("observations", event.observations());
        if (event.imageAnalysisFailed()) payload.put("imageAnalysisFailed", true);
        var workerKey = event.idempotencyKey() == null || event.idempotencyKey().isBlank()
                ? event.eventType().name()
                : event.idempotencyKey();
        var idempotencyKey = "ProductAgentExecution:" + execution.executionId()
                + ":" + workerKey + ":" + event.eventId();
        return new DomainEventService.AppendEvent(
                lifecycleType(event.eventType()), sessionSystemId(execution.prdId()),
                null, execution.prdId(), null, "product-agent", "worker", payload,
                execution.executionId(), null, idempotencyKey, event.eventId());
    }

    private DomainEventType lifecycleType(ProductAgentExecutionEvent.EventType type) {
        return switch (type) {
            case Started -> DomainEventType.ProductAgentExecutionStarted;
            case Heartbeat -> DomainEventType.ProductAgentExecutionHeartbeat;
            case Completed -> DomainEventType.ProductAgentExecutionCompleted;
            case Failed -> DomainEventType.ProductAgentExecutionFailed;
            case Cancelled -> DomainEventType.ProductAgentExecutionCancelled;
        };
    }

    private String sessionSystemId(String prdId) {
        return sessions.findById(prdId)
                .map(PrdSession::systemId)
                .orElse(null);
    }

    private void appendCompletionEvents(ProductAgentExecution execution, PrdSession session, PrdDraft draft,
                                        List<String> missing, String status, List<String> usedContextRefs) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", draft.title());
        payload.put("status", status);
        payload.put("executionId", execution.executionId());
        payload.put("contextBundleId", execution.contextBundleId());
        payload.put("usedContextRefs", usedContextRefs);
        append(DomainEventType.PRDUpdated, session.systemId(), session.prdId(), "product-agent",
                "PRDUpdated:" + session.prdId() + ":" + execution.executionId(), payload);
        if (!missing.isEmpty()) {
            append(DomainEventType.ClarificationRequested, session.systemId(), session.prdId(), "product-agent",
                    "ClarificationRequested:" + session.prdId() + ":" + execution.executionId(),
                    Map.of("missingFields", missing, "executionId", execution.executionId()));
        }
    }

    private void append(DomainEventType type, String systemId, String prdId, String actorId,
                        String idempotencyKey, Map<String, Object> payload) {
        events.append(new DomainEventService.AppendEvent(type, systemId, null, prdId, null, actorId,
                "control-plane", payload, prdId, null, idempotencyKey));
    }

    static List<String> missingFields(PrdDraft draft) {
        // missingFields 由控制面基于合并后的草稿确定性重算。
        var missing = new ArrayList<String>();
        if (draft.title() == null || draft.title().isBlank()) missing.add("title");
        if (draft.goal() == null || draft.goal().isBlank()) missing.add("goal");
        if (draft.acceptanceCriteria().isEmpty()) missing.add("acceptance_criteria");
        return List.copyOf(missing);
    }

    private int attempt(ProductAgentExecution current, ProductAgentExecutionEvent event) {
        return event.attempt() == null ? Math.max(current.attempt(), 1) : Math.max(event.attempt(), current.attempt());
    }

    private String stage(ProductAgentExecutionEvent event, String fallback) {
        return text(event.stage(), fallback);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String assistantMessage(String original, boolean analysisFailed) {
        var message = new StringBuilder(original == null ? "" : original);
        if (analysisFailed) message.append("\n图片分析不可用，已保留图片，不影响文字对话。");
        return message.toString();
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("消息附件不是合法 JSON", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Product Agent execution payload 不是合法 JSON", error);
        }
    }
}
