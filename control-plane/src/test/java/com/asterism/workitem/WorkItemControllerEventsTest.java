package com.asterism.workitem;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkItemControllerEventsTest {
    @Test
    void displayIdResolvesToInternalTimelineAndRemainsPublic() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");
        var event = event();
        when(workItems.findByDisplayWorkItemId("WI20260706001")).thenReturn(Optional.of(item()));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of(event));
        var controller = new WorkItemController(workItems, mock(TemporalCasePort.class), events, access,
                mock(com.asterism.prd.PrdSessionRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.asterism.git.GitIntegrationService.class), mock(com.asterism.git.GitLabClient.class));

        var detail = controller.detail("WI20260706001", actor);
        var timeline = controller.events("WI20260706001", actor);

        verify(access, times(2)).requireMember("sys-1", actor);
        assertThat(detail.workItemId()).isEqualTo("WI20260706001");
        assertThat(timeline).containsExactly(event);
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection("wi-1", "WI20260706001", "sys-1", "prd-1", "case-1", "任务",
                "activated", "approved", true, "Worker 已激活", "worker", "owner",
                false, 2, now, null, "requester", now, now);
    }

    private DomainEventRecord event() {
        return new DomainEventRecord(2L, "evt-2", "WorkItemActivated", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}",
                "case-1", "sig-1", "key-1", Instant.now());
    }
}
