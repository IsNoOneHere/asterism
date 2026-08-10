package com.asterism.artifact;

import java.time.Instant;

public record ArtifactTransition(
        String transitionId,
        String artifactId,
        ArtifactStatus fromStatus,
        ArtifactStatus toStatus,
        String actor,
        String note,
        String domainEventId,
        String commandHash,
        Instant createdAt) {
}
