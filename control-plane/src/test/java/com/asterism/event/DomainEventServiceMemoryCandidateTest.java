package com.asterism.event;

import com.asterism.memory.MemoryItem;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.projection.ProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DomainEventServiceMemoryCandidateTest {
    @Test
    void lifecycleEventCreatesCandidateMemoryAfterProjection() {
        var memories = mock(MemoryItemRepository.class);
        var fixture = fixture(memories, savedEvent(DomainEventType.ModificationCompleted));
        when(memories.existsBySystemIdAndContent(anyString(), anyString())).thenReturn(false);

        fixture.service.append(command(DomainEventType.ModificationCompleted));

        var captor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(fixture.aggregate).insert(captor.capture());
        assertThat(captor.getValue().systemId()).isEqualTo("sys-1");
        assertThat(captor.getValue().status()).isEqualTo("candidate");
        assertThat(captor.getValue().sourceEventId()).isEqualTo("evt-1");
    }

    @Test
    void prdConfirmedDoesNotCreateCandidateMemory() {
        var memories = mock(MemoryItemRepository.class);
        var fixture = fixture(memories, savedEvent(DomainEventType.PRDConfirmed));

        fixture.service.append(command(DomainEventType.PRDConfirmed));

        verify(fixture.aggregate, never()).insert(any());
    }

    @Test
    void duplicateCandidateContentIsNotSavedAgain() {
        var memories = mock(MemoryItemRepository.class);
        var fixture = fixture(memories, savedEvent(DomainEventType.ValidationPassed));
        when(memories.existsBySystemIdAndContent(anyString(), anyString())).thenReturn(true);

        fixture.service.append(command(DomainEventType.ValidationPassed));

        verify(fixture.aggregate, never()).insert(any());
    }

    private Fixture fixture(MemoryItemRepository memories, DomainEventRecord saved) {
        var aggregate = mock(JdbcAggregateTemplate.class);
        var service = new DomainEventService(events(saved), mock(ProjectionService.class), new ObjectMapper(), memories, aggregate);
        return new Fixture(service, aggregate);
    }

    private DomainEventRepository events(DomainEventRecord saved) {
        var events = mock(DomainEventRepository.class);
        when(events.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(events.save(any())).thenReturn(saved);
        return events;
    }

    private record Fixture(DomainEventService service, JdbcAggregateTemplate aggregate) {
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
