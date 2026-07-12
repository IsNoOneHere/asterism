package com.agentteam.v5.event;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v5/projections")
public class ProjectionController {
    private final DomainEventService events;

    public ProjectionController(DomainEventService events) {
        this.events = events;
    }

    @PostMapping
    DomainEventRecord ingest(@RequestBody ProjectionEventRequest request) {
        return events.append(new DomainEventService.AppendEvent(
                DomainEventType.valueOf(request.eventType()),
                request.systemId(),
                request.caseId(),
                request.prdId(),
                request.workItemId(),
                request.actorId(),
                "worker",
                request.payload() == null ? Map.of() : request.payload(),
                request.correlationId(),
                request.causationId(),
                request.idempotencyKey()));
    }

    public record ProjectionEventRequest(
            @NotBlank String eventType,
            @NotBlank String systemId,
            String caseId,
            String prdId,
            String workItemId,
            String actorId,
            Map<String, Object> payload,
            String correlationId,
            String causationId,
            @NotBlank String idempotencyKey) {
    }
}

