package com.agentteam.v5.prd;

import com.agentteam.v5.event.DomainEventService;
import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.memory.MemoryItemRepository;
import com.agentteam.v5.system.SystemProfileRepository;
import com.agentteam.v5.system.ExecutionReadinessService;
import com.agentteam.v5.temporal.TemporalCasePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        var temporal = mock(TemporalCasePort.class);
        var access = mock(SystemAccessService.class);
        var systems = mock(SystemProfileRepository.class);
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
        var memories = mock(MemoryItemRepository.class);
        when(memories.findBySystemIdAndStatus(anyString(), anyString())).thenReturn(List.of());
        var controller = new PrdController(sessions, messages, new FakeProductAgentAdapter(), events, temporal,
                objectMapper, new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        }, access, systems, memories, aggregate, mock(WorkItemIdGenerator.class), mock(ExecutionReadinessService.class));
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");

        var first = controller.message("sys-1", new PrdController.PrdMessageRequest(null, "把登录页加错误提示"), actor);
        var firstSession = saved.get();
        when(sessions.findById(first.prdId())).thenReturn(Optional.of(firstSession));

        var second = controller.message("sys-1", new PrdController.PrdMessageRequest(first.prdId(), "输入错误密码时页面显示明确错误提示"), actor);
        var secondSession = saved.get();
        var draft = objectMapper.readValue(secondSession.draftJson(), new TypeReference<Map<String, Object>>() {
        });
        var missing = objectMapper.readValue(secondSession.missingFields(), new TypeReference<List<String>>() {
        });

        assertThat(first.status()).isEqualTo("need_clarification");
        assertThat(second.status()).isEqualTo("waiting_user_confirm");
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
        var controller = new PrdController(sessions, mock(ConversationMessageRepository.class), mock(ProductAgentPort.class),
                mock(DomainEventService.class), mock(TemporalCasePort.class), objectMapper, mock(TransactionOperations.class),
                mock(SystemAccessService.class), mock(SystemProfileRepository.class), mock(MemoryItemRepository.class),
                mock(JdbcAggregateTemplate.class), mock(WorkItemIdGenerator.class), mock(ExecutionReadinessService.class));

        assertThatThrownBy(() -> controller.message("sys-2", new PrdController.PrdMessageRequest("prd-1", "继续"),
                new UsernamePasswordAuthenticationToken("user", "n/a")))
                .isInstanceOf(com.agentteam.v5.common.ApiException.class)
                .extracting(error -> ((com.agentteam.v5.common.ApiException) error).code())
                .isEqualTo("PRD_SYSTEM_MISMATCH");
    }
}
