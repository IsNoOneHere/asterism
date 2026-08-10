package com.asterism.artifact;

import com.fasterxml.jackson.databind.JsonNode;

public record ArtifactTransitionRequest(
        String kind,
        String transitionId,
        ArtifactRef artifact,
        ArtifactRef parent,
        ArtifactRef supersedes,
        ArtifactRef expectedHead,
        JsonNode content,
        String note) {
}
