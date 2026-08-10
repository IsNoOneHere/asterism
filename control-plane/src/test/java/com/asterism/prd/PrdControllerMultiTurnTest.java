package com.asterism.prd;

import com.asterism.context.ContextBundle;
import com.asterism.context.ContextItem;
import com.asterism.context.ContextRecallService;
import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdControllerMultiTurnTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messagePersistsCreatedExecutionAndReturnsWithoutAssistantMessage() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var executions = mock(ProductAgentExecutionRepository.class);
        var executionService = mock(ProductAgentExecutionService.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var savedExecution = new AtomicReference<ProductAgentExecution>();
        var savedMessages = new ArrayList<ConversationMessage>();
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(aggregate.insert(any(ProductAgentExecution.class))).thenAnswer(call -> {
            var execution = (ProductAgentExecution) call.getArgument(0);
            savedExecution.set(execution);
            return execution;
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            savedMessages.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(executionService.start(anyString())).thenAnswer(call -> savedExecution.get());
        var service = new PrdConversationService(sessions, messages, executions, executionService, events,
                objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), access,
                contextRecall(), aggregate, mock(com.asterism.attachment.AttachmentService.class));
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");

        var response = service.message(
                "sys-1", new PrdConversationService.PrdMessageRequest(null, "把登录页加错误提示"), actor);

        assertThat(response.status()).isEqualTo(ProductAgentExecutionStatus.CREATED);
        assertThat(response.executionId()).isEqualTo(savedExecution.get().executionId());
        assertThat(savedExecution.get().workflowId())
                .isEqualTo("product-agent-" + savedExecution.get().executionId());
        assertThat(savedMessages).extracting(ConversationMessage::senderType).containsExactly("user");
    }

    @Test
    void messageReturnsCreatedExecutionWhenInitialTemporalStartFails() {
        var savedExecution = new AtomicReference<ProductAgentExecution>();
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(aggregate.insert(any(ProductAgentExecution.class))).thenAnswer(call -> {
            savedExecution.set(call.getArgument(0));
            return savedExecution.get();
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        var executionService = mock(ProductAgentExecutionService.class);
        when(executionService.start(anyString())).thenThrow(new IllegalStateException("Product Agent workflow 启动失败，可重试"));
        var service = new PrdConversationService(
                mock(PrdSessionRepository.class), mock(ConversationMessageRepository.class),
                mock(ProductAgentExecutionRepository.class), executionService, mock(DomainEventService.class),
                objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class),
                contextRecall(), aggregate, mock(com.asterism.attachment.AttachmentService.class));

        var response = service.message(
                "sys-1", new PrdConversationService.PrdMessageRequest(null, "把登录页加错误提示"),
                new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.executionId()).isEqualTo(savedExecution.get().executionId());
        assertThat(response.status()).isEqualTo(ProductAgentExecutionStatus.CREATED);
    }

    @Test
    void productTurnReceivesCurrentMissingFieldsWithoutSystemKnowledge() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var now = java.time.Instant.now();
        var current = new PrdSession(
                "prd-1", "sys-1", "conv-1", null, null, null, null,
                "{}", "[]", "need_clarification", "user", null, null, now, now);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(current));
        var input = new ConversationMessage(
                "msg-input", "conv-1", "sys-1", "prd-1", "user",
                "写一个心跳接口", "[]", "[]", "user", now);
        when(messages.findById("msg-input")).thenReturn(Optional.of(input));
        when(messages.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(List.of(
                new ConversationMessage(
                        "msg-user", "conv-1", "sys-1", "prd-1", "user",
                        "接口必须沿用现有网关限制", "[]", "[]", "user", now),
                new ConversationMessage(
                        "msg-assistant", "conv-1", "sys-1", "prd-1", "assistant",
                        "路由是 /health，接口为 GET /internal/health", "[]", "[]", "product-agent", now),
                input));
        var systemKnowledge = new ContextItem(
                "KN:health", "system_knowledge", "both", "心跳接口",
                "路由: /health\n接口: GET /internal/health\n代码位置: src/health.py",
                List.of("health"), "route-index", "hash-kn", 2.0);
        var businessMemory = new ContextItem(
                "MEM:audience", "memory", "product", "目标用户",
                "值班负责人需要看到服务是否可用",
                List.of(), "manual", "hash-mem", 1.0);
        var bundle = new ContextBundle(
                "bundle-product", "sys-1", "prd-1", "product", "query-hash",
                List.of(systemKnowledge, businessMemory), java.time.Instant.now());
        var bundles = mock(com.asterism.context.ContextBundleStore.class);
        when(bundles.find("bundle-product")).thenReturn(Optional.of(bundle));
        var execution = new ProductAgentExecution(
                "exec-1", "prd-1", ProductAgentExecutionStatus.CREATED, "product-agent-exec-1",
                "msg-input", "bundle-product", "CREATED", 0, null,
                null, null, null, null, now, now);
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findById("exec-1")).thenReturn(Optional.of(execution));
        when(executions.recordStartAttempt(anyString(), any())).thenReturn(1);
        var command = new AtomicReference<ProductAgentExecutionPort.StartExecutionCommand>();
        var port = mock(ProductAgentExecutionPort.class);
        when(port.start(any())).thenAnswer(call -> {
            command.set(call.getArgument(0));
            return command.get().workflowId();
        });
        var service = new ProductAgentExecutionService(
                executions, sessions, messages, bundles, port, objectMapper, new PrdDraftCodec(objectMapper),
                new PrdCitationService(), mock(JdbcAggregateTemplate.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class), mock(DomainEventService.class),
                directTransactions());

        service.start("exec-1");

        assertThat(command.get().missingFields()).containsExactly("title", "goal", "acceptance_criteria");
        assertThat(command.get().conversationHistory()).extracting(ConversationMessage::senderType).containsExactly("user");
        assertThat(command.get().conversationHistory()).extracting(ConversationMessage::content)
                .containsExactly("接口必须沿用现有网关限制");
        assertThat(command.get().contextItems()).containsExactly(businessMemory);
        assertThat(bundle.items()).contains(systemKnowledge);
    }

    @Test
    void rejectsChangingSystemAfterPrdWasCreated() {
        var sessions = mock(PrdSessionRepository.class);
        var current = new PrdSession("prd-1", "sys-1", "conv-1", null, null, "标题", "目标", "{}", "[]",
                "need_clarification", "user", null, null, java.time.Instant.now(), java.time.Instant.now());
        when(sessions.findById("prd-1")).thenReturn(Optional.of(current));
        var service = new PrdConversationService(
                sessions, mock(ConversationMessageRepository.class), mock(ProductAgentExecutionRepository.class),
                mock(ProductAgentExecutionService.class), mock(DomainEventService.class), objectMapper,
                new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class),
                contextRecall(), mock(JdbcAggregateTemplate.class),
                mock(com.asterism.attachment.AttachmentService.class));

        assertThatThrownBy(() -> service.message("sys-2", new PrdConversationService.PrdMessageRequest("prd-1", "继续"),
                new UsernamePasswordAuthenticationToken("user", "n/a")))
                .isInstanceOf(com.asterism.common.ApiException.class)
                .extracting(error -> ((com.asterism.common.ApiException) error).code())
                .isEqualTo("PRD_SYSTEM_MISMATCH");
    }

    @Test
    void activeExecutionRejectsSecondMessage() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var now = java.time.Instant.now();
        var session = new PrdSession(
                "prd-1", "sys-1", "conv-1", null, null, "登录提示", "展示错误",
                "{}", "[]", "need_clarification", "user", null, null, now, now);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session));
        var execution = new ProductAgentExecution(
                "exec-1", "prd-1", ProductAgentExecutionStatus.RUNNING, "product-agent-exec-1",
                "msg-1", "bundle-1", "drafting", 1, null, now, null, now, null, now, now);
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findActiveByPrdId("prd-1")).thenReturn(Optional.of(execution));
        var service = new PrdConversationService(
                sessions, messages, executions, mock(ProductAgentExecutionService.class),
                mock(DomainEventService.class), objectMapper, new PrdDraftCodec(objectMapper), directTransactions(),
                mock(SystemAccessService.class), contextRecall(), mock(JdbcAggregateTemplate.class),
                mock(com.asterism.attachment.AttachmentService.class));
        var actor = new UsernamePasswordAuthenticationToken("user", "n/a");

        assertThatThrownBy(() -> service.message("sys-1",
                new PrdConversationService.PrdMessageRequest("prd-1", "重复发送"), actor))
                .isInstanceOf(com.asterism.common.ApiException.class)
                .extracting(error -> ((com.asterism.common.ApiException) error).code())
                .isEqualTo("PRD_ASSISTANT_PENDING");
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private ContextRecallService contextRecall() {
        var recall = mock(ContextRecallService.class);
        when(recall.recall(any())).thenAnswer(call -> {
            var query = (com.asterism.context.ContextRecallQuery) call.getArgument(0);
            return new ContextBundle("bundle-test", query.systemId(), query.prdId(), query.phase(),
                    "query-hash", List.of(), java.time.Instant.now());
        });
        return recall;
    }

}
