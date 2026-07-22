package com.asterism.event;

import com.asterism.memory.WorkItemMemoryLearningService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v5/projections")
public class ProjectionController {
    private final DomainEventService events;
    private final WorkItemMemoryLearningService memoryLearning;

    public ProjectionController(DomainEventService events, WorkItemMemoryLearningService memoryLearning) {
        this.events = events;
        this.memoryLearning = memoryLearning;
    }

    @PostMapping
    DomainEventRecord ingest(@RequestBody ProjectionEventRequest request) {
        var saved = events.append(new DomainEventService.AppendEvent(
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
        // 发布事件先幂等入库，再从已验证的修订意见提取待审批记忆；重试由候选去重收敛。
        memoryLearning.learn(saved);
        return saved;
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
