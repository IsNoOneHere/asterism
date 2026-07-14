package com.asterism.event;

import com.asterism.projection.ProjectionService;
import com.asterism.memory.MemoryItem;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.knowledge.WorkItemKnowledgeLearningService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DomainEventService {
    private static final Logger log = LoggerFactory.getLogger(DomainEventService.class);
    private static final Set<DomainEventType> MEMORY_CANDIDATE_EVENTS = Set.of(
            DomainEventType.WorkerBlocked,
            DomainEventType.ModificationCompleted,
            DomainEventType.PatchApplied,
            DomainEventType.ValidationFailed,
            DomainEventType.ValidationPassed,
            DomainEventType.ReleaseCompleted);
    private final DomainEventRepository events;
    private final ProjectionService projection;
    private final ObjectMapper objectMapper;
    private final MemoryItemRepository memories;
    private final JdbcAggregateTemplate aggregate;
    private final WorkItemKnowledgeLearningService knowledgeLearning;

    public DomainEventService(DomainEventRepository events, ProjectionService projection, ObjectMapper objectMapper,
                              MemoryItemRepository memories, JdbcAggregateTemplate aggregate,
                              WorkItemKnowledgeLearningService knowledgeLearning) {
        this.events = events;
        this.projection = projection;
        this.objectMapper = objectMapper;
        this.memories = memories;
        this.aggregate = aggregate;
        this.knowledgeLearning = knowledgeLearning;
    }

    @Transactional
    public DomainEventRecord append(AppendEvent command) {
        if (command.idempotencyKey() != null) {
            var existing = events.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                log.info("复用幂等事件 eventId={} key={}", existing.get().eventId(), command.idempotencyKey());
                return existing.get();
            }
        }
        var saved = events.save(command.toRecord(objectMapper));
        log.info("领域事件已入库 sequence={} type={} workItem={}", saved.sequence(), saved.eventType(), saved.workItemId());
        projection.apply(saved);
        createMemoryCandidate(saved);
        knowledgeLearning.learn(saved);
        return saved;
    }

    public boolean exists(String idempotencyKey) {
        return idempotencyKey != null && events.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    public long countSubmittedSignals(String workItemId, String signalName) {
        return events.countSubmittedSignals(workItemId, signalName);
    }

    public boolean hasUnrecoveredSignalFailure(String workItemId, String signalId) {
        var submitted = events.latestSubmittedSignalSequence(workItemId, signalId);
        var failed = events.latestFailedSignalSequence(workItemId, signalId);
        return failed > submitted;
    }

    public long countSignalFailures(String workItemId, String signalId) {
        return events.countSignalFailures(workItemId, signalId);
    }

    public List<DomainEventRecord> findByWorkItemId(String workItemId) {
        return events.findByWorkItemIdOrderBySequenceAsc(workItemId);
    }

    private void createMemoryCandidate(DomainEventRecord event) {
        var type = DomainEventType.valueOf(event.eventType());
        if (event.systemId() == null || !MEMORY_CANDIDATE_EVENTS.contains(type)) {
            return;
        }
        var content = event.eventType() + " " + event.payloadJson();
        if (memories.existsBySystemIdAndContent(event.systemId(), content)) {
            log.info("跳过重复 memory candidate system={} event={}", event.systemId(), event.eventId());
            return;
        }
        var now = Instant.now();
        aggregate.insert(new MemoryItem(
                "mem-" + UUID.randomUUID(),
                event.systemId(),
                content,
                "candidate",
                event.eventId(),
                null,
                "{}",
                "worker",
                now,
                null));
        log.info("自动沉淀 memory candidate system={} sourceEvent={}", event.systemId(), event.eventId());
    }

    public record AppendEvent(
            DomainEventType eventType,
            String systemId,
            String caseId,
            String prdId,
            String workItemId,
            String actorId,
            String source,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            String idempotencyKey) {
        DomainEventRecord toRecord(ObjectMapper objectMapper) {
            try {
                return new DomainEventRecord(
                        null,
                        "evt-" + UUID.randomUUID(),
                        eventType.name(),
                        "v5.0",
                        systemId,
                        caseId,
                        prdId,
                        workItemId,
                        actorId,
                        source,
                        objectMapper.writeValueAsString(payload == null ? Map.of() : payload),
                        correlationId,
                        causationId,
                        idempotencyKey,
                        Instant.now());
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("事件 payload 不是合法 JSON", error);
            }
        }
    }
}
