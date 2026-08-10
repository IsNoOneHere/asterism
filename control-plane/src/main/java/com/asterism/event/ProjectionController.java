package com.asterism.event;

import com.asterism.artifact.ArtifactEvidenceRequest;
import com.asterism.artifact.ArtifactTransitionRequest;
import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.memory.ArtifactMemoryLifecycleService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RestController
@RequestMapping("/api/v5/projections")
public class ProjectionController {
    private final ArtifactMemoryLifecycleService memoryLearning;
    private final ArtifactTransitionService artifactTransitions;

    public ProjectionController(ArtifactMemoryLifecycleService memoryLearning,
                                ArtifactTransitionService artifactTransitions) {
        this.memoryLearning = memoryLearning;
        this.artifactTransitions = artifactTransitions;
    }

    @PostMapping
    @Transactional
    ArtifactTransitionService.Result ingest(@RequestBody ProjectionEventRequest request) {
        var eventType = DomainEventType.valueOf(request.eventType());
        // Transition 是主动作；Artifact、Transition、Evidence 和 Domain Event 在同一事务提交。
        var result = artifactTransitions.ingest(new ArtifactTransitionService.EventMetadata(
                        eventType, request.systemId(), request.caseId(), request.prdId(), request.workItemId(),
                        request.actorId(), "worker", request.correlationId(), request.causationId(),
                        request.idempotencyKey()),
                request.payload(), request.artifactTransition(), request.artifactEvidence());
        // Artifact 提交后再异步提取候选，Memory 失败不会回滚主工作流。
        memoryLearning.schedule(result);
        return result;
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
            @NotBlank String idempotencyKey,
            ArtifactTransitionRequest artifactTransition,
            ArtifactEvidenceRequest artifactEvidence) {
    }
}
