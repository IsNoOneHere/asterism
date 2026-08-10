package com.asterism.artifact;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record Artifact(
        String artifactId,
        ArtifactType artifactType,
        String rootArtifactId,
        String systemId,
        String prdId,
        String workItemId,
        String caseId,
        int version,
        ArtifactStatus status,
        String parentArtifactId,
        String supersedesArtifactId,
        String expectedHeadArtifactId,
        JsonNode content,
        String contentHash,
        String idempotencyKey,
        String createdBy,
        Instant createdAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewNote) {
}
