package com.asterism.artifact;

import java.util.List;
import java.util.Map;

public record CodingArtifactContent(
        String summary,
        List<RepoChange> repoChanges,
        ExecutionOutcome executionOutcome,
        Map<String, String> baseRevisions) implements ArtifactContent {

    public CodingArtifactContent {
        repoChanges = repoChanges == null ? List.of() : List.copyOf(repoChanges);
        executionOutcome = executionOutcome == null
                ? new ExecutionOutcome(ExecutionStatus.completed, List.of()) : executionOutcome;
        baseRevisions = baseRevisions == null ? Map.of() : Map.copyOf(baseRevisions);
    }

    public record RepoChange(String repo, String diffPatch, List<String> changedPaths, String summary) {
        public RepoChange {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    public record ExecutionOutcome(ExecutionStatus status, List<String> blockers) {
        public ExecutionOutcome {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public enum ExecutionStatus {
        completed,
        blocked
    }

}
