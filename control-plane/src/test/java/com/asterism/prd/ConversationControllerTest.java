package com.asterism.prd;

import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationControllerTest {
    @Test
    void pollingTurnsExpiredPendingMessageIntoVisibleError() {
        var messages = mock(ConversationMessageRepository.class);
        var expired = message("assistant_pending", "", Instant.now().minusSeconds(121));
        var failed = message("assistant", "AI 暂时不可用，请重试", expired.createdAt());
        when(messages.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(expired), List.of(failed));
        when(messages.completePending("msg-1", "AI 暂时不可用，请重试")).thenReturn(1);
        var controller = new ConversationController(messages, mock(SystemAccessService.class), new ObjectMapper());

        var response = controller.messages("conv-1", new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(response.pendingAssistant()).isFalse();
        assertThat(response.messages()).singleElement().extracting(ConversationController.ConversationMessageView::content)
                .isEqualTo("AI 暂时不可用，请重试");
        verify(messages).completePending("msg-1", "AI 暂时不可用，请重试");
    }

    private ConversationMessage message(String sender, String content, Instant createdAt) {
        return new ConversationMessage("msg-1", "conv-1", "sys-1", "prd-1", sender, content,
                "[]", "[]", "product-agent", createdAt);
    }
}
