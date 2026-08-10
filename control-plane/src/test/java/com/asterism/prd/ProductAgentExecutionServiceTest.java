package com.asterism.prd;

import com.asterism.context.ContextBundle;
import com.asterism.context.ContextBundleStore;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.knowledge.KnowledgeMatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductAgentExecutionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completedWithoutStartedEventAppliesProjectionAndDuplicateIsIdempotent() {
        var now = Instant.now();
        var execution = new AtomicReference<>(withStartState(createdExecution(), 1, null));
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findById("exec-1")).thenAnswer(call -> Optional.of(execution.get()));
        when(executions.markCompleted(anyString(), anyString(), eq(1), any())).thenAnswer(call -> {
            if (!execution.get().status().active()) return 0;
            execution.set(withStatus(execution.get(), ProductAgentExecutionStatus.COMPLETED, null));
            return 1;
        });
        when(executions.attachResultMessage(anyString(), anyString(), any())).thenAnswer(call -> {
            execution.set(withStatus(execution.get(), ProductAgentExecutionStatus.COMPLETED, call.getArgument(1)));
            return 1;
        });
        var sessions = mock(PrdSessionRepository.class);
        var session = new PrdSession(
                "prd-1", "sys-1", "conv-1", null, null, null, null, "{}", "[]",
                "need_clarification", "user", null, null, now, now);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session));
        var messages = mock(ConversationMessageRepository.class);
        var input = new ConversationMessage(
                "msg-user", "conv-1", "sys-1", "prd-1", "user", "补充需求", "[]", "[]", "user", now);
        when(messages.findById("msg-user")).thenReturn(Optional.of(input));
        var bundles = mock(ContextBundleStore.class);
        when(bundles.find("bundle-1")).thenReturn(Optional.of(new ContextBundle(
                "bundle-1", "sys-1", "prd-1", "product", "hash", List.of(), now)));
        var aggregate = mock(JdbcAggregateTemplate.class);
        var projectedSession = new AtomicReference<PrdSession>();
        var assistantMessage = new AtomicReference<ConversationMessage>();
        var assistantCount = new AtomicInteger();
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            projectedSession.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.update(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            assistantCount.incrementAndGet();
            assistantMessage.set(call.getArgument(0));
            return assistantMessage.get();
        });
        var events = idempotentEvents();
        var service = service(executions, sessions, messages, bundles, aggregate, events);
        var candidate = Map.<String, Object>of(
                "artifactType", "PRODUCT",
                "content", Map.of("marker", "candidate-only"));
        var event = new ProductAgentExecutionEvent(
                "worker-event-completed-1", "worker-completed",
                ProductAgentExecutionEvent.EventType.Completed, "draft_completed", 1, null,
                new ProductAgentPort.DraftResult(
                        new ProductAgentPort.PrdPatch(
                                "登录提示", "让错误原因可见", "code_change", List.of("错误密码时显示提示")),
                        "PRD draft 已就绪，请确认。", Map.of()),
                candidate, List.of(), false);

        var first = service.apply("exec-1", event);
        var duplicate = service.apply("exec-1", event);

        assertThat(first.status()).isEqualTo(ProductAgentExecutionStatus.COMPLETED);
        assertThat(first.startedAt()).isNotNull();
        assertThat(duplicate.resultMessageId()).isEqualTo(first.resultMessageId());
        assertThat(assistantCount).hasValue(1);
        assertThat(projectedSession.get().status()).isEqualTo("waiting_user_confirm");
        assertThat(projectedSession.get().draftJson()).contains("登录提示", "错误密码时显示提示");
        assertThat(projectedSession.get().draftJson()).doesNotContain("candidate-only");
        assertThat(assistantMessage.get().content()).doesNotContain("candidate-only");
        var appended = ArgumentCaptor.forClass(DomainEventService.AppendEvent.class);
        verify(events, times(2)).append(appended.capture());
        var lifecycle = appended.getAllValues().stream()
                .filter(value -> value.eventType() == DomainEventType.ProductAgentExecutionCompleted)
                .toList();
        assertThat(lifecycle).singleElement().satisfies(value -> {
            assertThat(value.eventId()).isEqualTo("worker-event-completed-1");
            assertThat(value.idempotencyKey()).contains("exec-1", "worker-event-completed-1");
            assertThat(value.payload()).containsEntry("generatedArtifactCandidate", candidate);
            assertThat(value.payload().keySet()).doesNotContain("credentials", "configuration", "apiKey");
        });
    }

    @Test
    void failedEventIsTerminalAndDoesNotCreateAssistantMessage() {
        var execution = new AtomicReference<>(execution(ProductAgentExecutionStatus.RUNNING));
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findById("exec-1")).thenAnswer(call -> Optional.of(execution.get()));
        when(executions.markFailed(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            execution.set(withStatus(execution.get(), ProductAgentExecutionStatus.FAILED, null));
            return 1;
        });
        var aggregate = mock(JdbcAggregateTemplate.class);
        var events = idempotentEvents();
        var service = service(executions, mock(PrdSessionRepository.class),
                mock(ConversationMessageRepository.class), mock(ContextBundleStore.class), aggregate, events);
        service.apply("exec-1", new ProductAgentExecutionEvent(
                "worker-event-failed-1", null,
                ProductAgentExecutionEvent.EventType.Failed, "model_call", 1, "MODEL_FAILED",
                null, null, List.of(), false));
        var completed = new ProductAgentExecutionEvent(
                "worker-event-completed-late", null,
                ProductAgentExecutionEvent.EventType.Completed, "completed", 1, null,
                new ProductAgentPort.DraftResult(
                        new ProductAgentPort.PrdPatch("不应生效", null, null, null), "不应出现", Map.of()),
                null, List.of(), false);

        var result = service.apply("exec-1", completed);

        assertThat(result.status()).isEqualTo(ProductAgentExecutionStatus.FAILED);
        verify(executions).markFailed(eq("exec-1"), eq("model_call"), eq("MODEL_FAILED"), any());
        verify(executions, never()).markCompleted(anyString(), anyString(), anyInt(), any());
        verify(aggregate, never()).insert(any(ConversationMessage.class));
        var appended = ArgumentCaptor.forClass(DomainEventService.AppendEvent.class);
        verify(events, times(2)).append(appended.capture());
        assertThat(appended.getAllValues()).extracting(DomainEventService.AppendEvent::eventType)
                .containsExactly(
                        DomainEventType.ProductAgentExecutionFailed,
                        DomainEventType.ProductAgentExecutionCompleted);
    }

    @Test
    void heartbeatEventsWithDifferentEventIdsAreNotCollapsed() {
        var execution = new AtomicReference<>(execution(ProductAgentExecutionStatus.RUNNING));
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findById("exec-1")).thenAnswer(call -> Optional.of(execution.get()));
        when(executions.heartbeat(anyString(), anyString(), eq(1), any())).thenReturn(1);
        var events = idempotentEvents();
        var service = service(executions, mock(PrdSessionRepository.class),
                mock(ConversationMessageRepository.class), mock(ContextBundleStore.class),
                mock(JdbcAggregateTemplate.class), events);

        service.apply("exec-1", heartbeat("worker-heartbeat-1"));
        service.apply("exec-1", heartbeat("worker-heartbeat-2"));

        verify(executions, times(2)).heartbeat(eq("exec-1"), eq("drafting"), eq(1), any());
        var appended = ArgumentCaptor.forClass(DomainEventService.AppendEvent.class);
        verify(events, times(2)).append(appended.capture());
        assertThat(appended.getAllValues()).extracting(DomainEventService.AppendEvent::eventId)
                .containsExactly("worker-heartbeat-1", "worker-heartbeat-2");
        assertThat(appended.getAllValues()).extracting(DomainEventService.AppendEvent::idempotencyKey)
                .allSatisfy(key -> assertThat(key).contains("exec-1"))
                .doesNotHaveDuplicates();
    }

    @Test
    void blankEventIdIsRejectedBeforeAudit() {
        var events = mock(DomainEventService.class);
        var service = service(mock(ProductAgentExecutionRepository.class), mock(PrdSessionRepository.class),
                mock(ConversationMessageRepository.class), mock(ContextBundleStore.class),
                mock(JdbcAggregateTemplate.class), events);

        assertThatThrownBy(() -> service.apply("exec-1", new ProductAgentExecutionEvent(
                " ", null, ProductAgentExecutionEvent.EventType.Heartbeat, "drafting", 1,
                null, null, null, List.of(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        verify(events, never()).append(any());
    }

    @Test
    void definitiveStartFailureKeepsCreatedExecutionForRetry() {
        var execution = new AtomicReference<>(createdExecution());
        var executions = startRepository(execution);
        var port = mock(ProductAgentExecutionPort.class);
        when(port.start(any())).thenThrow(new RuntimeException("temporal unavailable"));
        var service = startService(executions, port);

        assertThatThrownBy(() -> service.start("exec-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("可重试");

        assertThat(execution.get().status()).isEqualTo(ProductAgentExecutionStatus.CREATED);
        assertThat(execution.get().attempt()).isEqualTo(1);
        assertThat(execution.get().failureCode()).isEqualTo("TEMPORAL_START_FAILED");
    }

    @Test
    void alreadyStartedAfterAmbiguousFailureIsTreatedAsSuccess() {
        var execution = new AtomicReference<>(createdExecution());
        var executions = startRepository(execution);
        var port = mock(ProductAgentExecutionPort.class);
        var ambiguous = new RuntimeException("deadline exceeded");
        var alreadyStarted = new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId("product-agent-exec-1").build(),
                "AsterismProductAgentWorkflow", ambiguous);
        when(port.start(any())).thenThrow(ambiguous).thenThrow(alreadyStarted);
        var service = startService(executions, port);

        assertThatThrownBy(() -> service.start("exec-1")).isInstanceOf(IllegalStateException.class);
        var recovered = service.start("exec-1");

        assertThat(recovered.status()).isEqualTo(ProductAgentExecutionStatus.CREATED);
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.failureCode()).isNull();
        verify(executions, times(2)).recordStartAttempt(eq("exec-1"), any());
        verify(executions).recordStartFailure(eq("exec-1"), eq("TEMPORAL_START_FAILED"), any());
    }

    private ProductAgentExecutionService service(
            ProductAgentExecutionRepository executions,
            PrdSessionRepository sessions,
            ConversationMessageRepository messages,
            ContextBundleStore bundles,
            JdbcAggregateTemplate aggregate,
            DomainEventService events) {
        return new ProductAgentExecutionService(
                executions, sessions, messages, bundles, mock(ProductAgentExecutionPort.class), objectMapper,
                new PrdDraftCodec(objectMapper), new PrdCitationService(), aggregate,
                mock(KnowledgeMatchService.class), events, mock(TransactionOperations.class));
    }

    private ProductAgentExecutionService startService(
            ProductAgentExecutionRepository executions,
            ProductAgentExecutionPort port) {
        var now = Instant.now();
        var sessions = mock(PrdSessionRepository.class);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(new PrdSession(
                "prd-1", "sys-1", "conv-1", null, null, null, null,
                "{}", "[]", "need_clarification", "user", null, null, now, now)));
        var messages = mock(ConversationMessageRepository.class);
        when(messages.findById("msg-user")).thenReturn(Optional.of(new ConversationMessage(
                "msg-user", "conv-1", "sys-1", "prd-1", "user", "补充需求",
                "[]", "[]", "user", now)));
        var bundles = mock(ContextBundleStore.class);
        when(bundles.find("bundle-1")).thenReturn(Optional.of(new ContextBundle(
                "bundle-1", "sys-1", "prd-1", "product", "hash", List.of(), now)));
        return new ProductAgentExecutionService(
                executions, sessions, messages, bundles, port, objectMapper,
                new PrdDraftCodec(objectMapper), new PrdCitationService(), mock(JdbcAggregateTemplate.class),
                mock(KnowledgeMatchService.class), mock(DomainEventService.class), directTransactions());
    }

    private ProductAgentExecutionRepository startRepository(AtomicReference<ProductAgentExecution> execution) {
        var executions = mock(ProductAgentExecutionRepository.class);
        when(executions.findById("exec-1")).thenAnswer(call -> Optional.of(execution.get()));
        when(executions.recordStartAttempt(eq("exec-1"), any())).thenAnswer(call -> {
            var current = execution.get();
            execution.set(withStartState(current, current.attempt() + 1, null));
            return 1;
        });
        when(executions.recordStartFailure(eq("exec-1"), anyString(), any())).thenAnswer(call -> {
            execution.set(withStartState(execution.get(), execution.get().attempt(), call.getArgument(1)));
            return 1;
        });
        return executions;
    }

    private DomainEventService idempotentEvents() {
        var events = mock(DomainEventService.class);
        Set<String> keys = new HashSet<>();
        when(events.exists(anyString())).thenAnswer(call -> keys.contains(call.getArgument(0)));
        when(events.append(any())).thenAnswer(call -> {
            var command = (DomainEventService.AppendEvent) call.getArgument(0);
            if (command.idempotencyKey() != null) keys.add(command.idempotencyKey());
            return null;
        });
        return events;
    }

    private ProductAgentExecutionEvent heartbeat(String eventId) {
        return new ProductAgentExecutionEvent(
                eventId, "same-worker-heartbeat", ProductAgentExecutionEvent.EventType.Heartbeat,
                "drafting", 1, null, null, null, List.of(), false);
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private ProductAgentExecution createdExecution() {
        var now = Instant.now();
        return new ProductAgentExecution(
                "exec-1", "prd-1", ProductAgentExecutionStatus.CREATED, "product-agent-exec-1",
                "msg-user", "bundle-1", "CREATED", 0, null,
                null, null, null, null, now, now);
    }

    private ProductAgentExecution withStartState(ProductAgentExecution current, int attempt, String failureCode) {
        return new ProductAgentExecution(
                current.executionId(), current.prdId(), current.status(), current.workflowId(),
                current.inputMessageId(), current.contextBundleId(), current.stage(), attempt, failureCode,
                current.startedAt(), current.completedAt(), current.lastHeartbeat(), current.resultMessageId(),
                current.createdAt(), Instant.now());
    }

    private ProductAgentExecution execution(ProductAgentExecutionStatus status) {
        var now = Instant.now();
        return new ProductAgentExecution(
                "exec-1", "prd-1", status, "product-agent-exec-1", "msg-user", "bundle-1",
                status.name(), 1, status == ProductAgentExecutionStatus.FAILED ? "MODEL_FAILED" : null,
                now, status.terminal() ? now : null, now, null, now, now);
    }

    private ProductAgentExecution withStatus(ProductAgentExecution current, ProductAgentExecutionStatus status,
                                             String resultMessageId) {
        return new ProductAgentExecution(
                current.executionId(), current.prdId(), status, current.workflowId(), current.inputMessageId(),
                current.contextBundleId(), status.name(), current.attempt(), current.failureCode(),
                current.startedAt() == null ? Instant.now() : current.startedAt(),
                Instant.now(), Instant.now(), resultMessageId,
                current.createdAt(), Instant.now());
    }
}
