package com.asterism.artifact;

import java.util.List;
import java.util.Map;

public record ArtifactGraph(
        String rootArtifactId,
        List<Artifact> nodes,
        List<Edge> edges,
        Map<ArtifactType, ArtifactRef> effectiveHeads) {

    public record Edge(String fromArtifactId, String toArtifactId, EdgeType edgeType) {
    }

    public enum EdgeType {
        DERIVED_FROM,
        SUPERSEDES
    }
}
