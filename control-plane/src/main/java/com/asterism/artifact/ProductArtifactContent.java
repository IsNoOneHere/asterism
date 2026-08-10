package com.asterism.artifact;

import java.util.List;
import java.util.Map;

public record ProductArtifactContent(
        String title,
        String goal,
        String scope,
        List<String> acceptanceCriteria,
        List<ConfirmedTarget> targets,
        Map<String, List<String>> citations,
        String requirementManifestId,
        List<String> auditRefs) implements ArtifactContent {

    public ProductArtifactContent {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        targets = targets == null ? List.of() : List.copyOf(targets);
        citations = citations == null ? Map.of() : Map.copyOf(citations);
        auditRefs = auditRefs == null ? List.of() : List.copyOf(auditRefs);
    }

    public record ConfirmedTarget(
            String entryId,
            String repo,
            String kind,
            String title,
            String routePath,
            List<String> apiEndpoints,
            List<String> codeRefs) {

        public ConfirmedTarget {
            apiEndpoints = apiEndpoints == null ? List.of() : List.copyOf(apiEndpoints);
            codeRefs = codeRefs == null ? List.of() : List.copyOf(codeRefs);
        }
    }
}
