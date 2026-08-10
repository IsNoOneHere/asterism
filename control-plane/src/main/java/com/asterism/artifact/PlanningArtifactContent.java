package com.asterism.artifact;

import java.util.List;
import java.util.Map;

public record PlanningArtifactContent(
        String planMarkdown,
        Map<String, String> baseRevisions,
        List<String> acceptanceCriteriaRefs,
        List<String> repositories,
        List<String> evidenceRefs,
        List<String> risks,
        List<String> openQuestions) implements ArtifactContent {

    public PlanningArtifactContent {
        baseRevisions = baseRevisions == null ? Map.of() : Map.copyOf(baseRevisions);
        acceptanceCriteriaRefs = acceptanceCriteriaRefs == null ? List.of() : List.copyOf(acceptanceCriteriaRefs);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        risks = risks == null ? List.of() : List.copyOf(risks);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    }
}
