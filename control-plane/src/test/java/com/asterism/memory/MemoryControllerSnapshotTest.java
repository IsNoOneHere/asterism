package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactType;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryControllerSnapshotTest {
    @Test
    void listExposesArtifactTraceability() {
        var memories = mock(MemoryItemRepository.class);
        var candidates = mock(MemoryCandidateRepository.class);
        var service = mock(MemoryCandidateService.class);
        var artifacts = mock(ArtifactService.class);
        var item = memory("mem-1", MemoryStatus.ACTIVE);
        when(memories.findTop100BySystemIdAndStatusOrderByCreatedAtDesc("sys-1", MemoryStatus.ACTIVE))
                .thenReturn(List.of(item));
        when(service.targetRefs(List.of(item))).thenReturn(Map.of("mem-1", List.of("page-login")));
        when(service.evidenceRefs(item)).thenReturn(List.of("evt-1"));
        when(artifacts.require("art-plan-1")).thenReturn(artifact());
        var controller = new MemoryController(
                memories, candidates, service, artifacts, mock(SystemAccessService.class));

        var values = controller.list(
                "sys-1", MemoryStatus.ACTIVE,
                new UsernamePasswordAuthenticationToken("member", "n/a"));

        assertThat(values).singleElement().satisfies(view -> {
            assertThat(view.memoryType()).isEqualTo(MemoryType.DECISION);
            assertThat(view.artifactSource().artifactId()).isEqualTo("art-plan-1");
            assertThat(view.artifactSource().workItemId()).isEqualTo("wi-1");
            assertThat(view.targetRefs()).containsExactly("page-login");
        });
    }

    @Test
    void approvalConfirmsCandidateBeforeCreatingActiveMemory() {
        var memories = mock(MemoryItemRepository.class);
        var candidateRepository = mock(MemoryCandidateRepository.class);
        var service = mock(MemoryCandidateService.class);
        var artifacts = mock(ArtifactService.class);
        var candidate = candidate();
        var item = memory("mem-1", MemoryStatus.ACTIVE);
        when(candidateRepository.findById("candidate-1")).thenReturn(Optional.of(candidate));
        when(service.approve(eq(candidate), any(), eq("owner"))).thenReturn(item);
        when(service.targetRefs(List.of(item))).thenReturn(Map.of());
        when(service.evidenceRefs(item)).thenReturn(List.of("evt-1"));
        when(artifacts.require("art-plan-1")).thenReturn(artifact());
        var controller = new MemoryController(
                memories, candidateRepository, service, artifacts, mock(SystemAccessService.class));

        var view = controller.approve(
                "candidate-1",
                new MemoryController.ApprovalRequest(
                        MemoryType.DECISION, "数据库决策", "采用 PostgreSQL 保存项目事实。",
                        0.9, MemoryApplicability.PROJECT, null, List.of()),
                new UsernamePasswordAuthenticationToken("owner", "n/a"));

        assertThat(view.status()).isEqualTo(MemoryStatus.ACTIVE);
        verify(service).approve(eq(candidate), any(), eq("owner"));
    }

    @Test
    void rejectKeepsCandidateForAudit() {
        var repository = mock(MemoryCandidateRepository.class);
        var service = mock(MemoryCandidateService.class);
        var candidate = candidate();
        var rejected = new MemoryCandidate(
                candidate.candidateId(), candidate.systemId(), candidate.projectScope(), candidate.memoryType(),
                candidate.artifactSourceId(), candidate.sourceKind(), candidate.title(), candidate.content(),
                candidate.confidence(), candidate.applicability(), candidate.expiresAt(),
                MemoryCandidateStatus.REJECTED, candidate.targetRefs(), candidate.evidenceRefs(),
                candidate.normalizedContentHash(), candidate.sourceEventId(), candidate.createdBy(),
                "owner", "不适用于整个项目", null, candidate.createdAt(), Instant.now());
        when(repository.findById("candidate-1")).thenReturn(Optional.of(candidate));
        when(service.reject(candidate, "owner", "不适用于整个项目")).thenReturn(rejected);
        var artifacts = mock(ArtifactService.class);
        when(artifacts.require("art-plan-1")).thenReturn(artifact());
        var controller = new MemoryController(
                mock(MemoryItemRepository.class), repository, service, artifacts,
                mock(SystemAccessService.class));

        var view = controller.reject(
                "candidate-1", new MemoryController.RejectRequest("不适用于整个项目"),
                new UsernamePasswordAuthenticationToken("owner", "n/a"));

        assertThat(view.status()).isEqualTo(MemoryCandidateStatus.REJECTED);
        assertThat(view.reviewNote()).isEqualTo("不适用于整个项目");
    }

    private MemoryCandidate candidate() {
        return new MemoryCandidate(
                "candidate-1", "sys-1", "sys-1", MemoryType.DECISION, "art-plan-1",
                MemoryCandidateService.ARTIFACT_APPROVED, "数据库决策", "采用 PostgreSQL。",
                0.84, MemoryApplicability.PROJECT, null, MemoryCandidateStatus.PENDING,
                "[]", "[\"evt-1\"]", "hash-1", "evt-1", "memory-extractor",
                null, null, null, Instant.now(), null);
    }

    private MemoryItem memory(String id, MemoryStatus status) {
        var now = Instant.now();
        return new MemoryItem(
                id, "sys-1", "sys-1", MemoryType.DECISION, "art-plan-1",
                "数据库决策", "采用 PostgreSQL。", 0.9, MemoryApplicability.PROJECT,
                null, status, "candidate-1", "candidate-1", "artifact:art-plan-1",
                "[\"evt-1\"]", "hash-1", "evt-1", "owner", "{}",
                "memory-extractor", now, now);
    }

    private Artifact artifact() {
        var now = Instant.now();
        return new Artifact(
                "art-plan-1", ArtifactType.PLANNING, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-product-1", null, null, new ObjectMapper().createObjectNode(),
                "hash", "plan-1", "worker", now, "owner", now, "批准");
    }
}
