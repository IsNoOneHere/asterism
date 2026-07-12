package com.agentteam.v5.workitem;

import com.agentteam.v5.event.DomainEventRecord;
import com.agentteam.v5.event.DomainEventService;
import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.projection.WorkItemProjection;
import com.agentteam.v5.projection.WorkItemProjectionRepository;
import com.agentteam.v5.temporal.TemporalCasePort;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkItemControllerEventsTest {
    @Test
    void eventsEndpointChecksSystemAccessAndReturnsTimeline() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");
        var event = event();
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item()));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of(event));
        var controller = new WorkItemController(workItems, mock(TemporalCasePort.class), events, access);

        var timeline = controller.events("wi-1", actor);

        verify(access).requireMember("sys-1", actor);
        assertThat(timeline).containsExactly(event);
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection("wi-1", "sys-1", "prd-1", "case-1", "任务",
                "activated", "approved", true, "Worker 已激活", "worker", "owner",
                false, 2, now, null, "requester", now, now);
    }

    private DomainEventRecord event() {
        return new DomainEventRecord(2L, "evt-2", "WorkItemActivated", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}",
                "case-1", "sig-1", "key-1", Instant.now());
    }
}
