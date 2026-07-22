package com.asterism.event;

import com.asterism.memory.WorkItemMemoryLearningService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionControllerMemoryLearningTest {
    @Test
    void releaseProjectionInvokesMemoryLearningAfterIdempotentAppend() {
        var events = mock(DomainEventService.class);
        var learning = mock(WorkItemMemoryLearningService.class);
        var saved = new DomainEventRecord(10L, "evt-release", "ReleaseCompleted", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}", "case-1", null,
                "release-1", Instant.now());
        when(events.append(any())).thenReturn(saved);
        var controller = new ProjectionController(events, learning);

        controller.ingest(new ProjectionController.ProjectionEventRequest(
                "ReleaseCompleted", "sys-1", "case-1", "prd-1", "wi-1", "worker",
                Map.of(), "case-1", null, "release-1"));

        verify(learning).learn(saved);
    }
}
