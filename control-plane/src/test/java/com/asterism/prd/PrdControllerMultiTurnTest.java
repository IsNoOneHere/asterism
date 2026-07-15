package com.asterism.prd;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.memory.MemoryItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
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
    void secondMessageMergesAcceptanceCriteriaWithoutOverwritingFirstDraft() throws Exception {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var saved = new AtomicReference<PrdSession>();
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(aggregate.insert(any(PrdSession.class))).thenAnswer(call -> {
            var session = (PrdSession) call.getArgument(0);
            saved.set(session);
            return session;
        });
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            var session = (PrdSession) call.getArgument(0);
            saved.set(session);
            return session;
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(0L, 1L);
        when(messages.completePending(anyString(), anyString())).thenReturn(1);
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var service = new PrdConversationService(sessions, messages, new FakeProductAgentAdapter(), events,
                objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), access, memories, aggregate,
                mock(com.asterism.attachment.AttachmentService.class), mock(com.asterism.vision.ImageAnalysisService.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class), Runnable::run);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");

        var first = service.message("sys-1", new PrdConversationService.PrdMessageRequest(null, "把登录页加错误提示"), actor);
        var firstSession = saved.get();
        when(sessions.findById(first.prdId())).thenReturn(Optional.of(firstSession));

        var second = service.message("sys-1", new PrdConversationService.PrdMessageRequest(first.prdId(), "输入错误密码时页面显示明确错误提示"), actor);
        var secondSession = saved.get();
        var draft = objectMapper.readValue(secondSession.draftJson(), new TypeReference<Map<String, Object>>() {
        });
        var missing = objectMapper.readValue(secondSession.missingFields(), new TypeReference<List<String>>() {
        });

        assertThat(first.status()).isEqualTo("need_clarification");
        assertThat(second.assistantPending()).isTrue();
        assertThat(secondSession.status()).isEqualTo("waiting_user_confirm");
        assertThat(secondSession.title()).isEqualTo(firstSession.title());
        assertThat(secondSession.goal()).isEqualTo("把登录页加错误提示");
        assertThat(draft.get("goal")).isEqualTo("把登录页加错误提示");
        assertThat(draft.get("acceptanceCriteria")).isEqualTo(List.of("输入错误密码时页面显示明确错误提示"));
        assertThat(missing).isEmpty();
    }

    @Test
    void rejectsChangingSystemAfterPrdWasCreated() {
        var sessions = mock(PrdSessionRepository.class);
        var current = new PrdSession("prd-1", "sys-1", "conv-1", null, null, "标题", "目标", "{}", "[]",
                "need_clarification", "user", null, null, java.time.Instant.now(), java.time.Instant.now());
        when(sessions.findById("prd-1")).thenReturn(Optional.of(current));
        var service = new PrdConversationService(sessions, mock(ConversationMessageRepository.class), mock(ProductAgentPort.class),
                mock(DomainEventService.class), objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class),
                mock(MemoryItemRepository.class), mock(JdbcAggregateTemplate.class),
                mock(com.asterism.attachment.AttachmentService.class), mock(com.asterism.vision.ImageAnalysisService.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class), Runnable::run);

        assertThatThrownBy(() -> service.message("sys-2", new PrdConversationService.PrdMessageRequest("prd-1", "继续"),
                new UsernamePasswordAuthenticationToken("user", "n/a")))
                .isInstanceOf(com.asterism.common.ApiException.class)
                .extracting(error -> ((com.asterism.common.ApiException) error).code())
                .isEqualTo("PRD_SYSTEM_MISMATCH");
    }

    @Test
    void imageAnalysisFailureKeepsAttachmentAndContinuesTextConversation() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(0L);
        when(messages.completePending(anyString(), anyString())).thenReturn(1);
        when(aggregate.insert(any(PrdSession.class))).thenAnswer(call -> call.getArgument(0));
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var attachments = mock(com.asterism.attachment.AttachmentService.class);
        var attachment = new com.asterism.attachment.Attachment("att-1", "sys-1", "user", "screen.png",
                "image/png", 12, "hash", "aa/hash", java.time.Instant.now());
        when(attachments.requireForSystem("att-1", "sys-1")).thenReturn(attachment);
        when(attachments.read(attachment)).thenReturn(new byte[]{1, 2, 3});
        var imageAnalysis = mock(com.asterism.vision.ImageAnalysisService.class);
        when(imageAnalysis.analyze(org.mockito.ArgumentMatchers.eq("sys-1"),
                org.mockito.ArgumentMatchers.eq(attachment), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenThrow(new IllegalStateException("vision down"));
        var service = new PrdConversationService(sessions, messages, new FakeProductAgentAdapter(),
                mock(DomainEventService.class), objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class), memories,
                aggregate, attachments, imageAnalysis,
                mock(com.asterism.knowledge.KnowledgeMatchService.class), Runnable::run);

        var response = service.message("sys-1",
                new PrdConversationService.PrdMessageRequest(null, "验收：页面显示明确错误", List.of("att-1")),
                new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(response.assistantPending()).isTrue();
        org.mockito.Mockito.verify(messages).completePending(anyString(),
                org.mockito.ArgumentMatchers.contains("图片分析不可用"));
    }

    @Test
    void productAgentFailureKeepsUserMessageAndNextTurnCanSucceed() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var productAgent = mock(ProductAgentPort.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var savedSession = new AtomicReference<PrdSession>();
        var savedMessages = new ArrayList<ConversationMessage>();
        when(sessions.findById(anyString())).thenAnswer(call -> Optional.ofNullable(savedSession.get()));
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(0L, 1L);
        when(messages.completePending(anyString(), anyString())).thenReturn(1);
        when(messages.findByConversationIdOrderByCreatedAtAsc(anyString())).thenReturn(savedMessages);
        when(aggregate.insert(any(PrdSession.class))).thenAnswer(call -> {
            savedSession.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            savedSession.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            savedMessages.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.update(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        when(productAgent.updateDraft(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("agent down"))
                .thenReturn(new ProductAgentPort.DraftResult("登录提示", Map.of("title", "登录提示"), List.of(), "已完成"));
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var service = new PrdConversationService(sessions, messages, productAgent, mock(DomainEventService.class),
                objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class), memories, aggregate,
                mock(com.asterism.attachment.AttachmentService.class), mock(com.asterism.vision.ImageAnalysisService.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class), Runnable::run);
        var actor = new UsernamePasswordAuthenticationToken("user", "n/a");

        var failed = service.message("sys-1", new PrdConversationService.PrdMessageRequest(null, "登录页报错"), actor);
        var retried = service.message("sys-1", new PrdConversationService.PrdMessageRequest(failed.prdId(), "请重试"), actor);

        assertThat(failed.assistantPending()).isTrue();
        assertThat(retried.assistantPending()).isTrue();
        assertThat(savedMessages).extracting(ConversationMessage::senderType)
                .containsExactly("user", "assistant_pending", "user", "assistant_pending");
        org.mockito.Mockito.verify(messages).completePending(anyString(),
                org.mockito.ArgumentMatchers.eq("AI 暂时不可用，请重试"));
        assertThat(savedSession.get().draftJson()).contains("登录提示");
    }

    @Test
    void pendingTurnReturnsImmediatelyAndRejectsSecondMessage() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var session = new AtomicReference<PrdSession>();
        var pending = new AtomicReference<ConversationMessage>();
        when(sessions.findById(anyString())).thenAnswer(call -> Optional.ofNullable(session.get()));
        when(messages.findFirstByConversationIdAndSenderTypeOrderByCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(call -> Optional.ofNullable(pending.get()));
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(0L);
        when(aggregate.insert(any(PrdSession.class))).thenAnswer(call -> {
            session.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            var value = (ConversationMessage) call.getArgument(0);
            if (PrdConversationService.PENDING_SENDER.equals(value.senderType())) pending.set(value);
            return value;
        });
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var executor = new HoldingExecutor();
        var productAgent = mock(ProductAgentPort.class);
        var service = new PrdConversationService(sessions, messages, productAgent, mock(DomainEventService.class),
                objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class), memories, aggregate,
                mock(com.asterism.attachment.AttachmentService.class), mock(com.asterism.vision.ImageAnalysisService.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class), executor);
        var actor = new UsernamePasswordAuthenticationToken("user", "n/a");

        var accepted = service.message("sys-1", new PrdConversationService.PrdMessageRequest(null, "登录页报错"), actor);

        assertThat(accepted.assistantPending()).isTrue();
        assertThat(executor.task).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(productAgent);
        assertThatThrownBy(() -> service.message("sys-1",
                new PrdConversationService.PrdMessageRequest(accepted.prdId(), "重复发送"), actor))
                .isInstanceOf(com.asterism.common.ApiException.class)
                .extracting(error -> ((com.asterism.common.ApiException) error).code())
                .isEqualTo("PRD_ASSISTANT_PENDING");
    }

    @Test
    void expiredPendingBecomesErrorBeforeNextTurnIsAccepted() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var now = java.time.Instant.now();
        var current = new PrdSession("prd-1", "sys-1", "conv-1", null, null, "标题", "目标", "{}", "[]",
                "need_clarification", "user", null, null, now, now);
        var expired = new ConversationMessage("pending-old", "conv-1", "sys-1", "prd-1", "assistant_pending", "",
                "[]", "[]", "product-agent", now.minusSeconds(121));
        when(sessions.findById("prd-1")).thenReturn(Optional.of(current));
        when(messages.findFirstByConversationIdAndSenderTypeOrderByCreatedAtAsc("conv-1", "assistant_pending"))
                .thenReturn(Optional.of(expired));
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(1L);
        when(messages.findByConversationIdOrderByCreatedAtAsc("conv-1")).thenReturn(List.of(expired));
        when(messages.completePending(anyString(), anyString())).thenReturn(1);
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var service = new PrdConversationService(sessions, messages, mock(ProductAgentPort.class),
                mock(DomainEventService.class), objectMapper, new PrdDraftCodec(objectMapper), directTransactions(), mock(SystemAccessService.class),
                memories, aggregate, mock(com.asterism.attachment.AttachmentService.class),
                mock(com.asterism.vision.ImageAnalysisService.class), mock(com.asterism.knowledge.KnowledgeMatchService.class),
                new HoldingExecutor());

        var accepted = service.message("sys-1", new PrdConversationService.PrdMessageRequest("prd-1", "继续"),
                new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(accepted.assistantPending()).isTrue();
        org.mockito.Mockito.verify(messages).completePending("pending-old", "AI 暂时不可用，请重试");
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private static final class HoldingExecutor implements TaskExecutor {
        private Runnable task;

        @Override
        public void execute(Runnable task) {
            this.task = task;
        }
    }
}
