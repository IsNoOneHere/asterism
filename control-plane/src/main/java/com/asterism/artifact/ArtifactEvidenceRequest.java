package com.asterism.artifact;

import com.fasterxml.jackson.databind.JsonNode;

public record ArtifactEvidenceRequest(
        String evidenceId,
        ArtifactRef artifact,
        ArtifactEvidenceType evidenceType,
        String transitionId,
        JsonNode payload) {
}
