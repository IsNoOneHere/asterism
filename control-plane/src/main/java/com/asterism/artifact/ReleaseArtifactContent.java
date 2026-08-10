package com.asterism.artifact;

import java.time.Instant;
import java.util.List;

/** 发布成功后物化的不可变清单；失败尝试继续由 Workflow 与 Evidence 表达。 */
public record ReleaseArtifactContent(
        String releaseId,
        String releaseMode,
        String targetKey,
        List<RepositoryRelease> repositories,
        ArtifactRef codingArtifact,
        ArtifactRef validationArtifact,
        Instant completedAt) implements ArtifactContent {

    public record RepositoryRelease(
            String repo,
            String branch,
            String commitHash,
            Integer mrIid,
            String mrUrl,
            String finalState,
            List<String> changedPaths) {
    }
}
