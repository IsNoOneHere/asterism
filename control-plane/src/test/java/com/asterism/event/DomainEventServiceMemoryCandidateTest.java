package com.asterism.event;

import com.asterism.knowledge.WorkItemKnowledgeLearningService;
import com.asterism.projection.ProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DomainEventServiceMemoryCandidateTest {
    @Test
    void lifecycleEventRemainsInAuditAndKnowledgeFlowsOnly() {
        var projection = mock(ProjectionService.class);
        var knowledge = mock(WorkItemKnowledgeLearningService.class);
        var saved = savedEvent(DomainEventType.ModificationCompleted);
        var service = new DomainEventService(events(saved), projection, new ObjectMapper(), knowledge);

        service.append(command(DomainEventType.ModificationCompleted));

        verify(projection).apply(saved);
        verify(knowledge).learn(saved);
    }

    @Test
    void idempotentEventStillSkipsDownstreamProcessing() {
        var saved = savedEvent(DomainEventType.ModificationCompleted);
        var events = events(saved);
        when(events.findByIdempotencyKey("key-1")).thenReturn(Optional.of(saved));
        var projection = mock(ProjectionService.class);
        var knowledge = mock(WorkItemKnowledgeLearningService.class);
        var service = new DomainEventService(events, projection, new ObjectMapper(), knowledge);

        service.append(command(DomainEventType.ModificationCompleted));

        verify(events, never()).save(any());
        verifyNoInteractions(projection, knowledge);
    }

    private DomainEventRepository events(DomainEventRecord saved) {
        var events = mock(DomainEventRepository.class);
        when(events.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(events.save(any())).thenReturn(saved);
        return events;
    }

    private DomainEventService.AppendEvent command(DomainEventType type) {
        return new DomainEventService.AppendEvent(
                type,
                "sys-1",
                "case-1",
                "prd-1",
                "wi-1",
                "worker",
                "worker",
                Map.of("summary", "done"),
                "case-1",
                "sig-1",
                "key-1");
    }

    private DomainEventRecord savedEvent(DomainEventType type) {
        return new DomainEventRecord(
                1L,
                "evt-1",
                type.name(),
                "v5.0",
                "sys-1",
                "case-1",
                "prd-1",
                "wi-1",
                "worker",
                "worker",
                "{\"summary\":\"done\"}",
                "case-1",
                "sig-1",
                "key-1",
                Instant.now());
    }
}
