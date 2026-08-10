package com.asterism.context;

import com.asterism.artifact.ArtifactContextBuilder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/context-snapshots")
public class ContextController {
    private final ArtifactContextBuilder contexts;

    public ContextController(ArtifactContextBuilder contexts) {
        this.contexts = contexts;
    }

    @PostMapping
    ArtifactContextBuilder.ArtifactContextSnapshot snapshot(
            @Valid @RequestBody SnapshotRequest request) {
        // 跨阶段只按已批准 Artifact 引用构建上下文，不拼接上游 Session Transcript。
        return contexts.build(new ArtifactContextBuilder.Request(
                request.systemId(), request.prdId(), request.workItemId(), request.requirementManifestId(),
                request.phase(), request.productArtifact(), request.planningArtifact(),
                request.previousArtifact(), request.gitBaseRevisions()));
    }

    public record SnapshotRequest(
            @NotBlank String systemId,
            @NotBlank String prdId,
            @NotBlank String workItemId,
            @NotBlank String requirementManifestId,
            @NotBlank String phase,
            com.asterism.artifact.ArtifactRef productArtifact,
            com.asterism.artifact.ArtifactRef planningArtifact,
            com.asterism.artifact.ArtifactRef previousArtifact,
            java.util.Map<String, String> gitBaseRevisions) {
    }
}
