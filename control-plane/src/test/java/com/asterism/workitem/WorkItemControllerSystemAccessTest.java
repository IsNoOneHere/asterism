package com.asterism.workitem;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemControllerSystemAccessTest {
    @Test
    void ownerFromAnotherSystemCannotApproveWorkItem() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var actor = new UsernamePasswordAuthenticationToken("owner-a", "n/a");
        when(workItems.findById("wi-b")).thenReturn(Optional.of(item()));
        when(workItems.lockById("wi-b")).thenReturn(Optional.of(item()));
        when(events.findByWorkItemId("wi-b")).thenReturn(List.of());
        doThrow(new AccessDeniedException("非系统 owner/admin 无权操作"))
                .when(access).requireOwnerOrAdmin("system-b", actor);
        var service = new WorkItemActionService(workItems, temporal, events, access,
                mock(AgentConfigurationService.class), new ObjectMapper(), directTransactions());

        assertThatThrownBy(() -> service.submit("wi-b", "owner_approved", null, actor))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(temporal);
        verify(events, never()).append(any());
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection(
                "wi-b", "WI20260706001", "system-b", "prd-b", "case-b", "B 系统任务",
                "waiting_owner_approval", "pending", false, "等待负责人审批", "owner", "owner-b",
                false, 1, null, null, "requester-b", now, now);
    }
}
