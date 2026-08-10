package com.asterism.event;

import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.memory.ArtifactMemoryLifecycleService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionControllerMemoryLearningTest {
    @Test
    void artifactProjectionSchedulesMemoryExtractionAfterTransition() {
        var learning = mock(ArtifactMemoryLifecycleService.class);
        var transitions = mock(ArtifactTransitionService.class);
        var saved = new DomainEventRecord(10L, "evt-release", "ReleaseCompleted", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}", "case-1", null,
                "release-1", Instant.now());
        when(transitions.ingest(any(), any(), any(), any()))
                .thenReturn(new ArtifactTransitionService.Result(saved, null, null, null));
        var controller = new ProjectionController(learning, transitions);

        controller.ingest(new ProjectionController.ProjectionEventRequest(
                "ReleaseCompleted", "sys-1", "case-1", "prd-1", "wi-1", "worker",
                Map.of(), "case-1", null, "release-1", null, null));

        verify(learning).schedule(any(ArtifactTransitionService.Result.class));
    }
}
