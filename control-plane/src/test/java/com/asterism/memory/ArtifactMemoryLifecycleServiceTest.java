package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactRef;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.artifact.ArtifactType;
import com.asterism.event.DomainEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactMemoryLifecycleServiceTest {
    @Test
    void rejectedCodingOutdatesUnvalidatedCandidateBeforeExtractingFailure() {
        var artifacts = mock(ArtifactService.class);
        var extractor = mock(ArtifactMemoryExtractor.class);
        var candidates = mock(MemoryCandidateService.class);
        var artifact = new Artifact(
                "art-code-1", ArtifactType.CODING, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1,
                ArtifactStatus.REJECTED, "art-plan-1", null, null,
                new ObjectMapper().createObjectNode(), "hash", "key", "worker",
                Instant.now(), "worker", Instant.now(), "验证失败");
        var event = new DomainEventRecord(
                1L, "evt-validation", "ValidationFailed", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}",
                "case-1", null, "validation-1", Instant.now());
        var result = new ArtifactTransitionService.Result(
                event, ArtifactRef.from(artifact), null, null);
        when(artifacts.require(artifact.artifactId())).thenReturn(artifact);
        when(extractor.extract(artifact, event, null)).thenReturn(List.of());
        var service = new ArtifactMemoryLifecycleService(
                artifacts, extractor, candidates, Runnable::run);

        service.schedule(result);

        verify(candidates).outdateRejectedCodingCandidate(artifact.artifactId());
        verify(extractor).extract(artifact, event, null);
        verify(candidates).refreshArtifactStatuses(artifact.rootArtifactId());
    }
}
