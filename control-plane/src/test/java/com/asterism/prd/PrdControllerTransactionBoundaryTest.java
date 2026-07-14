package com.asterism.prd;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdControllerTransactionBoundaryTest {
    @Test
    void confirmStartsTemporalAfterDatabaseTransaction() {
        var order = new ArrayList<String>();
        var controller = controller(order, null);

        controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(order.indexOf("tx-end")).isLessThan(order.indexOf("temporal-start"));
        assertThat(order).containsSubsequence("tx-start", "save:case_starting", "event:PRDConfirmed", "tx-end", "temporal-start");
        assertThat(order).contains("event:OwnerApprovalRequested");
    }

    @Test
    void confirmPassesSystemProfileToTemporalCaseInput() {
        var order = new ArrayList<String>();
        var holder = new AtomicTemporalCommand();
        var controller = controller(order, null, holder);

        controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(holder.command.repoPath()).isEqualTo("/repo/demo");
        assertThat(holder.command.allowedPaths()).containsExactly("src", "README.md");
        assertThat(holder.command.forbiddenPaths()).containsExactly("secrets");
        assertThat(holder.command.testCommands()).containsExactly("mvn test");
        assertThat(holder.command.executionProvider()).isEqualTo("claude_sdk");
        assertThat(holder.command.claudeMaxTurns()).isEqualTo(40);
        assertThat(holder.command.executionTimeoutSeconds()).isEqualTo(900);
        assertThat(holder.command.prd().title()).isEqualTo("登录页错误提示");
        assertThat(holder.command.prd().goal()).isEqualTo("把登录页加错误提示");
        assertThat(holder.command.prd().acceptanceCriteria()).containsExactly("错误密码时显示提示");
        assertThat(holder.command.prd().draftJson()).containsEntry("goal", "把登录页加错误提示");
    }

    @Test
    void confirmRecordsRetryableStateWhenTemporalStartFails() {
        var order = new ArrayList<String>();
        var controller = controller(order, new RuntimeException("temporal down"));

        assertThatThrownBy(() -> controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("可重试");

        assertThat(order).contains("save:case_start_failed", "event:TemporalCaseStartFailed");
        assertThat(order).doesNotContain("event:OwnerApprovalRequested");
    }

    @Test
    void confirmCanRetryAfterCaseStartFailed() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "case_start_failed");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(order).contains("temporal-start", "event:OwnerApprovalRequested");
    }

    @Test
    void confirmReturnsCurrentStateWhenAlreadyStarted() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "waiting_owner_approval");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(order).doesNotContain("temporal-start");
    }

    @Test
    void confirmReusesWorkItemAllocatedByConcurrentRequest() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "waiting_user_confirm", "case_starting");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(response.lifecycleStatus()).isEqualTo("case_starting");
        assertThat(order).doesNotContain("temporal-start", "save:case_starting", "event:PRDConfirmed");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure) {
        return controller(order, startFailure, null, "waiting_user_confirm");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder) {
        return controller(order, startFailure, holder, "waiting_user_confirm");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder, String sessionStatus) {
        return controller(order, startFailure, holder, sessionStatus, sessionStatus);
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder,
                                     String visibleStatus, String lockedStatus) {
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var productAgent = mock(ProductAgentPort.class);
        var events = mock(DomainEventService.class);
        var temporal = mock(TemporalCasePort.class);
        var access = mock(SystemAccessService.class);
        var systems = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var workItemIds = mock(WorkItemIdGenerator.class);
        var readiness = mock(ExecutionReadinessService.class);
        when(readiness.readiness(any())).thenReturn(new ExecutionReadinessService.SystemReadiness(
                "sys-1", true, Instant.now(), "claude_sdk", List.of(), List.of()));
        when(workItemIds.nextId()).thenReturn("WI202607114827");
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session(visibleStatus)), Optional.of(session(lockedStatus)));
        when(systems.findById("sys-1")).thenReturn(Optional.of(system()));
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            var session = (PrdSession) call.getArgument(0);
            order.add("save:" + session.status());
            return session;
        });
        when(events.append(any())).thenAnswer(call -> {
            var event = (DomainEventService.AppendEvent) call.getArgument(0);
            order.add("event:" + event.eventType().name());
            return null;
        });
        when(temporal.startCase(any())).thenAnswer(call -> {
            order.add("temporal-start");
            if (holder != null) {
                holder.command = call.getArgument(0);
            }
            if (startFailure != null) {
                throw startFailure;
            }
            return "case-prd-1";
        });
        TransactionOperations tx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                order.add("tx-start");
                var result = action.doInTransaction(null);
                order.add("tx-end");
                return result;
            }
        };
        return new PrdController(sessions, messages, productAgent, events, temporal, new ObjectMapper(), tx, access, systems,
                mock(MemoryItemRepository.class), aggregate, workItemIds, readiness,
                mock(com.asterism.attachment.AttachmentService.class), mock(com.asterism.vision.ImageAnalysisService.class),
                mock(com.asterism.knowledge.KnowledgeMatchService.class));
    }

    private PrdSession session(String status) {
        var now = Instant.now();
        var allocated = !"waiting_user_confirm".equals(status);
        return new PrdSession(
                "prd-1",
                "sys-1",
                "conv-prd-1",
                allocated ? "WI202607114827" : null,
                allocated ? "case-prd-1" : null,
                "登录页错误提示",
                "把登录页加错误提示",
                "{\"goal\":\"把登录页加错误提示\",\"acceptanceCriteria\":[\"错误密码时显示提示\"]}",
                "[]",
                status,
                "requester",
                allocated ? "requester" : null,
                allocated ? now : null,
                now,
                now);
    }

    private SystemProfile system() {
        var now = Instant.now();
        return new SystemProfile(
                "sys-1",
                "Demo",
                "demo",
                "/repo/demo",
                "owner",
                "[\"src\",\"README.md\"]",
                "[\"secrets\"]",
                "[\"mvn test\"]",
                "{\"executionProvider\":\"claude_sdk\",\"claudeMaxTurns\":40,\"executionTimeoutSeconds\":900}",
                "{}",
                "seed",
                now,
                now);
    }

    private static final class AtomicTemporalCommand {
        private TemporalCasePort.StartCaseCommand command;
    }
}
