package com.asterism.prd;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.context.ContextRecallService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrdDraftUpdateTest {
    private final UsernamePasswordAuthenticationToken actor =
            new UsernamePasswordAuthenticationToken("user", "n/a");

    @Test
    void importedWaitingInputCanBeEditedAndMovedToUserConfirmation() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var access = mock(SystemAccessService.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var events = mock(DomainEventService.class);
        var savedSession = new AtomicReference<PrdSession>();
        var savedMessage = new AtomicReference<ConversationMessage>();
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session("waiting_input")));
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            savedSession.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            savedMessage.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var service = service(sessions, messages, access, aggregate, events);

        var response = service.updateDraft("prd-1", "登录提示", "明确展示错误",
                List.of("错误密码时显示中文提示"), actor);

        assertThat(response.status()).isEqualTo("waiting_user_confirm");
        assertThat(response.missingFields()).isEmpty();
        assertThat(response.draft().get("acceptanceCriteria"))
                .isEqualTo(List.of("错误密码时显示中文提示"));
        assertThat(savedSession.get().status()).isEqualTo("waiting_user_confirm");
        assertThat(savedMessage.get().senderType()).isEqualTo("user");
        assertThat(savedMessage.get().content()).contains("手工更新 PRD", "错误密码时显示中文提示");
        assertThat(response.draft().get("citations").toString()).contains("MSG:");
        verify(events).append(org.mockito.ArgumentMatchers.argThat(event ->
                "manual_edit".equals(event.payload().get("source"))));
        verify(access).requireMember("sys-1", actor);
    }

    @Test
    void failedTurnCanBeCompletedManually() {
        var sessions = mock(PrdSessionRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var savedSession = new AtomicReference<PrdSession>();
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session("turn_failed")));
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            savedSession.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        var service = service(sessions, mock(ConversationMessageRepository.class), mock(SystemAccessService.class),
                aggregate, mock(DomainEventService.class));

        var response = service.updateDraft("prd-1", "登录提示", "明确展示错误",
                List.of("错误密码时显示中文提示"), actor);

        assertThat(response.status()).isEqualTo("waiting_user_confirm");
        assertThat(savedSession.get().status()).isEqualTo("waiting_user_confirm");
        assertThat(savedSession.get().draftJson()).contains("错误密码时显示中文提示");
    }

    @Test
    void rejectsManualUpdateOutsideEditableStates() {
        var sessions = mock(PrdSessionRepository.class);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session("waiting_owner_approval")));
        var service = service(sessions, mock(ConversationMessageRepository.class), mock(SystemAccessService.class),
                mock(JdbcAggregateTemplate.class), mock(DomainEventService.class));

        assertThatThrownBy(() -> service.updateDraft("prd-1", "标题", null, null, actor))
                .isInstanceOf(com.asterism.common.ApiException.class)
                .extracting(error -> ((com.asterism.common.ApiException) error).code())
                .isEqualTo("PRD_DRAFT_NOT_EDITABLE");
    }

    @Test
    void requiresSystemMembershipForManualUpdate() {
        var sessions = mock(PrdSessionRepository.class);
        var access = mock(SystemAccessService.class);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session("need_clarification")));
        doThrow(new AccessDeniedException("forbidden")).when(access).requireMember("sys-1", actor);
        var service = service(sessions, mock(ConversationMessageRepository.class), access,
                mock(JdbcAggregateTemplate.class), mock(DomainEventService.class));

        assertThatThrownBy(() -> service.updateDraft("prd-1", "标题", null, null, actor))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletesDraftEvenWhenWorkItemAlreadyExists() {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var access = mock(SystemAccessService.class);
        var now = Instant.now();
        var confirmed = new PrdSession("prd-1", "sys-1", "conv-1", "wi-1", "case-1", "标题", "目标",
                "{}", "[]", "waiting_owner_approval", "user", null, null, now, now);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(confirmed));
        var service = service(sessions, messages, access, mock(JdbcAggregateTemplate.class),
                mock(DomainEventService.class));

        service.deleteDraft("prd-1", actor);

        verify(access).requireMember("sys-1", actor);
        verify(sessions).markDeleted(eq("prd-1"), any(Instant.class));
        verifyNoInteractions(messages);
    }

    private PrdConversationService service(PrdSessionRepository sessions, ConversationMessageRepository messages,
                                           SystemAccessService access, JdbcAggregateTemplate aggregate,
                                           DomainEventService events) {
        var objectMapper = new ObjectMapper();
        return new PrdConversationService(sessions, messages, mock(ProductAgentPort.class), events, objectMapper,
                new PrdDraftCodec(objectMapper), mock(TransactionOperations.class), access,
                mock(ContextRecallService.class), new PrdCitationService(), aggregate,
                mock(com.asterism.attachment.AttachmentService.class),
                mock(com.asterism.vision.ImageAnalysisService.class), mock(com.asterism.knowledge.KnowledgeMatchService.class),
                Runnable::run);
    }

    private PrdSession session(String status) {
        var now = Instant.now();
        return new PrdSession("prd-1", "sys-1", "conv-1", null, null, "登录提示", "明确展示错误",
                "{\"title\":\"登录提示\",\"goal\":\"明确展示错误\",\"scope\":\"code_change\",\"acceptanceCriteria\":[]}",
                "[\"acceptance_criteria\"]", status, "user", null, null, now, now);
    }
}
