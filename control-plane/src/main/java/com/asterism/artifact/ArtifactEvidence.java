package com.asterism.artifact;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ArtifactEvidence(
        String evidenceId,
        String artifactId,
        String evidenceType,
        JsonNode payload,
        String transitionId,
        String domainEventId,
        String actor,
        String commandHash,
        Instant createdAt) {
}
