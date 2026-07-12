package com.agentteam.v5.workitem;

import com.agentteam.v5.event.DomainEventService;
import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.projection.WorkItemProjection;
import com.agentteam.v5.projection.WorkItemProjectionRepository;
import com.agentteam.v5.temporal.TemporalCasePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemControllerSignalAttemptTest {
    @Test
    void nonOwnerSignalsUseIncreasingAttempts() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item()));
        when(events.countSubmittedSignals("wi-1", "rework")).thenReturn(0L, 1L);
        var controller = new WorkItemController(workItems, temporal, events, access);
        var actor = new UsernamePasswordAuthenticationToken("owner", "n/a");

        controller.submitSignal("wi-1", "rework", actor);
        controller.submitSignal("wi-1", "rework", actor);

        var captor = ArgumentCaptor.forClass(TemporalCasePort.SignalCaseCommand.class);
        verify(temporal, times(2)).signalCase(captor.capture());
        assertThat(captor.getAllValues().stream().map(TemporalCasePort.SignalCaseCommand::signalId).toList())
                .containsExactly("rework-wi-1-1", "rework-wi-1-2");
        verify(events, times(2)).append(any());
    }

    @Test
    void ownerApprovalCanRetryAfterSignalFailure() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item()));
        when(events.exists("owner-approved-wi-1")).thenReturn(true);
        when(events.hasUnrecoveredSignalFailure("wi-1", "owner-approved-wi-1")).thenReturn(true, true);
        when(events.countSignalFailures("wi-1", "owner-approved-wi-1")).thenReturn(1L);
        doThrow(new RuntimeException("temporal down"))
                .doNothing()
                .when(temporal).signalCase(any());
        var controller = new WorkItemController(workItems, temporal, events, access);
        var actor = new UsernamePasswordAuthenticationToken("owner", "n/a");

        assertThatThrownBy(() -> controller.ownerApproval("wi-1", actor))
                .isInstanceOf(IllegalStateException.class);
        var response = controller.ownerApproval("wi-1", actor);

        assertThat(response.status()).isEqualTo("submitted");
        verify(temporal, times(2)).signalCase(any());
        verify(events, times(3)).append(any());
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection(
                "wi-1",
                "sys-1",
                "prd-1",
                "case-1",
                "登录页错误提示",
                "validation_failed",
                "approved",
                false,
                "等待重改",
                "owner",
                "owner",
                false,
                10,
                now,
                null,
                "requester",
                now,
                now);
    }
}
