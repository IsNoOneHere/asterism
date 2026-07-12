package com.agentteam.v5.workitem;

import com.agentteam.v5.event.DomainEventService;
import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.projection.WorkItemProjection;
import com.agentteam.v5.projection.WorkItemProjectionRepository;
import com.agentteam.v5.temporal.TemporalCasePort;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        doThrow(new AccessDeniedException("非系统 owner/admin 无权操作"))
                .when(access).requireOwnerOrAdmin("system-b", actor);
        var controller = new WorkItemController(workItems, temporal, events, access);

        assertThatThrownBy(() -> controller.ownerApproval("wi-b", actor))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(temporal);
        verify(events, never()).append(any());
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection(
                "wi-b",
                "system-b",
                "prd-b",
                "case-b",
                "B 系统任务",
                "waiting_owner_approval",
                "pending",
                false,
                "等待负责人审批",
                "owner",
                "owner-b",
                false,
                1,
                null,
                null,
                "requester-b",
                now,
                now);
    }
}

