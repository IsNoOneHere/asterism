package com.asterism.prd;

import com.asterism.context.ContextBundleStore;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConversationControllerTest {
    @Test
    void getConversationIsReadOnlyAndReturnsExecutionViews() {
        var messages = mock(ConversationMessageRepository.class);
        var user = message("user", "补充验收标准", Instant.now());
        when(messages.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(user));
        var executions = mock(ProductAgentExecutionService.class);
        var active = view(ProductAgentExecutionStatus.RUNNING);
        when(executions.latestView("prd-1")).thenReturn(active);
        var controller = new ConversationController(messages, mock(SystemAccessService.class), new ObjectMapper(),
                mock(ContextBundleStore.class), executions);

        var response = controller.messages("conv-1", new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(response.activeExecution()).isEqualTo(active);
        assertThat(response.latestExecution()).isEqualTo(active);
        assertThat(response.messages()).singleElement().extracting(ConversationController.ConversationMessageView::content)
                .isEqualTo("补充验收标准");
        verify(messages).findByConversationIdOrderByCreatedAtAsc("conv-1");
        verifyNoMoreInteractions(messages);
    }

    private ConversationMessage message(String sender, String content, Instant createdAt) {
        return new ConversationMessage("msg-1", "conv-1", "sys-1", "prd-1", sender, content,
                "[]", "[]", "product-agent", createdAt);
    }

    private ProductAgentExecutionView view(ProductAgentExecutionStatus status) {
        var now = Instant.now();
        return new ProductAgentExecutionView(
                "exec-1", "prd-1", status, "product-agent-exec-1", "msg-1", "bundle-1",
                status.name(), 1, null, now, null, now, null, now, now);
    }
}
