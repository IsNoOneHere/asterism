package com.asterism.event;

import com.asterism.projection.ProjectionService;
import com.asterism.knowledge.WorkItemKnowledgeLearningService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class DomainEventService {
    private static final Logger log = LoggerFactory.getLogger(DomainEventService.class);
    private final DomainEventRepository events;
    private final ProjectionService projection;
    private final ObjectMapper objectMapper;
    private final WorkItemKnowledgeLearningService knowledgeLearning;

    public DomainEventService(DomainEventRepository events, ProjectionService projection, ObjectMapper objectMapper,
                              WorkItemKnowledgeLearningService knowledgeLearning) {
        this.events = events;
        this.projection = projection;
        this.objectMapper = objectMapper;
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
            String idempotencyKey,
            String eventId) {

        public AppendEvent(
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
            this(eventType, systemId, caseId, prdId, workItemId, actorId, source, payload,
                    correlationId, causationId, idempotencyKey, null);
        }

        DomainEventRecord toRecord(ObjectMapper objectMapper) {
            try {
                return new DomainEventRecord(
                        null,
                        eventId == null || eventId.isBlank() ? "evt-" + UUID.randomUUID() : eventId,
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
