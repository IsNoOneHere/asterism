package com.asterism.context;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v5/context-snapshots")
public class ContextController {
    private final RequirementContextManifestService manifests;

    public ContextController(RequirementContextManifestService manifests) {
        this.manifests = manifests;
    }

    @PostMapping
    RequirementContextManifestService.ExecutionContextSnapshot snapshot(
            @Valid @RequestBody SnapshotRequest request) {
        // Worker 只能凭 PRD 已冻结的 manifest 读取需求上下文，不能自行创建新快照。
        return manifests.executionSnapshot(
                request.systemId(), request.prdId(), request.workItemId(), request.requirementManifestId(),
                request.goal(), request.draft());
    }

    public record SnapshotRequest(
            @NotBlank String systemId,
            @NotBlank String prdId,
            @NotBlank String workItemId,
            @NotBlank String requirementManifestId,
            String goal,
            Map<String, Object> draft) {
    }
}
