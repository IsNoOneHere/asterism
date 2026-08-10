package com.asterism.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactServiceValidationTest {
    @Test
    void workerBlockedWithoutFormalDiffCannotCreateCodingArtifact() {
        var repository = mock(ArtifactRepository.class);
        var artifacts = new ArtifactService(repository, new ObjectMapper());
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, "art-product-1", null);
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, product.rootArtifactId(), product.artifactId());
        when(repository.findById(product.artifactId())).thenReturn(Optional.of(product));
        when(repository.findById(planning.artifactId())).thenReturn(Optional.of(planning));
        when(repository.findHead(product.rootArtifactId(), ArtifactType.PRODUCT))
                .thenReturn(Optional.of(product));
        when(repository.findHead(product.rootArtifactId(), ArtifactType.PLANNING))
                .thenReturn(Optional.of(planning));

        var content = new CodingArtifactContent(
                "执行被阻塞", List.of(),
                new CodingArtifactContent.ExecutionOutcome(
                        CodingArtifactContent.ExecutionStatus.blocked, List.of("未生成正式 Diff")),
                Map.of("main", "base-1"));

        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.CODING,
                new ArtifactService.Metadata("sys-1", "prd-1", "wi-1", "case-1", "worker"),
                ArtifactRef.from(planning), null, null, content, "coding-blocked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正式 Git Diff");
    }

    private Artifact artifact(String id, ArtifactType type, String root, String parent) {
        return new Artifact(
                id, type, root, "sys-1", "prd-1", "wi-1", "case-1", 1,
                ArtifactStatus.APPROVED, parent, null, null,
                new ObjectMapper().createObjectNode(), "hash-" + id, "key-" + id,
                "worker", Instant.parse("2026-07-29T00:00:00Z"),
                "owner", Instant.parse("2026-07-29T00:00:00Z"), "批准");
    }
}
