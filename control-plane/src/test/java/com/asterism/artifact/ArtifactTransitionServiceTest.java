package com.asterism.artifact;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactTransitionServiceTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ArtifactRepository repository = mock(ArtifactRepository.class);
    private final DomainEventService events = mock(DomainEventService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArtifactTransitionService service =
            new ArtifactTransitionService(artifacts, repository, events, objectMapper);

    @Test
    void codingProposalBindsEvidenceToCreatedArtifact() {
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.APPROVED,
                "art-product-1", "art-product-1", null);
        var coding = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.PROPOSED,
                "art-product-1", planning.artifactId(), null);
        var transition = new ArtifactTransitionRequest(
                "ProposeCodingArtifact", "transition-code-1", null,
                ArtifactRef.from(planning), null, null,
                objectMapper.valueToTree(new CodingArtifactContent(
                        "完成实现", List.of(new CodingArtifactContent.RepoChange(
                        "main", "diff --git a/a b/a", List.of("a"), "摘要")),
                        new CodingArtifactContent.ExecutionOutcome(
                                CodingArtifactContent.ExecutionStatus.completed, List.of()),
                        Map.of("main", "base-1"))),
                "");
        var evidence = new ArtifactEvidenceRequest(
                "evidence-code-1", null, ArtifactEvidenceType.WorkerBlocked,
                transition.transitionId(), objectMapper.valueToTree(Map.of("changedPaths", List.of("a"))));
        when(repository.findTransition(transition.transitionId())).thenReturn(Optional.empty());
        when(artifacts.calculateHash(transition)).thenReturn("transition-hash");
        when(artifacts.createProposal(
                eq(ArtifactType.CODING), any(), eq(ArtifactRef.from(planning)), eq(null),
                eq(null), any(CodingArtifactContent.class), eq(transition.transitionId())))
                .thenReturn(new ArtifactService.Mutation(coding, null));
        when(events.append(any())).thenReturn(event(DomainEventType.WorkerBlocked));
        when(repository.findEvidence(evidence.evidenceId())).thenReturn(Optional.empty());
        when(artifacts.calculateHash(any(ArtifactTransitionCommand.AppendArtifactEvidence.class)))
                .thenReturn("evidence-hash");
        when(artifacts.requireExact(ArtifactRef.from(coding))).thenReturn(coding);
        when(repository.findTransition(transition.transitionId())).thenReturn(
                Optional.empty(),
                Optional.of(new ArtifactTransition(
                        transition.transitionId(), coding.artifactId(), null, ArtifactStatus.PROPOSED,
                        "worker", "", "event-1", "transition-hash",
                        Instant.parse("2026-07-29T00:00:00Z"))));

        var result = service.ingest(
                metadata(DomainEventType.WorkerBlocked),
                Map.of("summary", "完成实现"), transition, evidence);

        assertThat(result.artifactRef()).isEqualTo(ArtifactRef.from(coding));
        var captor = ArgumentCaptor.forClass(ArtifactEvidence.class);
        verify(repository).insertEvidence(captor.capture());
        assertThat(captor.getValue().artifactId()).isEqualTo(coding.artifactId());
    }

    @Test
    void validationFailureRejectsExactCodingArtifactAndKeepsEvidence() {
        var proposed = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.PROPOSED,
                "art-product-1", "art-plan-1", null);
        var rejected = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.REJECTED,
                "art-product-1", "art-plan-1", null);
        var transition = new ArtifactTransitionRequest(
                "RejectCodingArtifact", "transition-reject-1", ArtifactRef.from(proposed),
                null, null, null, null, "pytest 未通过");
        var evidence = new ArtifactEvidenceRequest(
                "evidence-validation-1", ArtifactRef.from(proposed), ArtifactEvidenceType.ValidationFailed,
                transition.transitionId(), objectMapper.valueToTree(Map.of("failedCommand", "pytest")));
        when(repository.findTransition(transition.transitionId())).thenReturn(Optional.empty());
        when(artifacts.calculateHash(transition)).thenReturn("transition-hash");
        when(artifacts.reject(
                ArtifactRef.from(proposed), null, "worker", "pytest 未通过"))
                .thenReturn(new ArtifactService.Mutation(rejected, null));
        when(events.append(any())).thenReturn(event(DomainEventType.ValidationFailed));
        when(repository.findEvidence(evidence.evidenceId())).thenReturn(Optional.empty());
        when(artifacts.calculateHash(any(ArtifactTransitionCommand.AppendArtifactEvidence.class)))
                .thenReturn("evidence-hash");
        when(artifacts.requireExact(ArtifactRef.from(rejected))).thenReturn(rejected);
        when(repository.findTransition(transition.transitionId())).thenReturn(
                Optional.empty(),
                Optional.of(new ArtifactTransition(
                        transition.transitionId(), rejected.artifactId(), ArtifactStatus.PROPOSED,
                        ArtifactStatus.REJECTED, "worker", "pytest 未通过", "event-1",
                        "transition-hash", Instant.parse("2026-07-29T00:00:00Z"))));

        var result = service.ingest(
                metadata(DomainEventType.ValidationFailed),
                Map.of("failedCommand", "pytest"), transition, evidence);

        assertThat(result.artifactRef().status()).isEqualTo(ArtifactStatus.REJECTED);
        verify(artifacts).reject(ArtifactRef.from(proposed), null, "worker", "pytest 未通过");
        verify(repository).insertEvidence(any(ArtifactEvidence.class));
    }

    @Test
    void reusedTransitionIdRejectsDifferentCommand() {
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.PROPOSED,
                "art-product-1", "art-product-1", null);
        var request = new ArtifactTransitionRequest(
                "ApprovePlanningArtifact", "transition-plan-1", ArtifactRef.from(planning),
                null, null, null, null, "");
        when(artifacts.calculateHash(request)).thenReturn("new-command-hash");
        when(repository.findTransition(request.transitionId())).thenReturn(Optional.of(
                new ArtifactTransition(
                        request.transitionId(), planning.artifactId(), ArtifactStatus.PROPOSED,
                        ArtifactStatus.APPROVED, "worker", "", "event-1",
                        "old-command-hash", Instant.parse("2026-07-29T00:00:00Z"))));

        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.CodingPlanApproved), Map.of(), request, null))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("不同 parent、expectedHead、ArtifactRef 或 Content");
    }

    @Test
    void manifestRefreshLocksTransitionBeforeIdempotencyLookup() {
        when(repository.findTransition("transition-refresh-1"))
                .thenThrow(new IllegalStateException("停止在幂等查询"));

        assertThatThrownBy(() -> service.refreshProductManifest(
                metadata(DomainEventType.RequirementContextRefreshed), null,
                "manifest-2", "transition-refresh-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("停止在幂等查询");

        var ordered = org.mockito.Mockito.inOrder(repository);
        ordered.verify(repository).lockCommand("transition:transition-refresh-1");
        ordered.verify(repository).findTransition("transition-refresh-1");
    }

    @Test
    void reusedTransitionReturnsTheOriginalStatusAfterArtifactAdvances() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED,
                "art-product-1", null, null);
        var approvedPlanning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.APPROVED,
                "art-product-1", product.artifactId(), null);
        var request = new ArtifactTransitionRequest(
                "ProposePlanningArtifact", "transition-plan-propose-1", null,
                ArtifactRef.from(product), null, null,
                objectMapper.valueToTree(new PlanningArtifactContent(
                        "# 计划", Map.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of())),
                "");
        var original = new ArtifactTransition(
                request.transitionId(), approvedPlanning.artifactId(), null,
                ArtifactStatus.PROPOSED, "worker", "", "event-1",
                "same-command-hash", Instant.parse("2026-07-29T00:00:00Z"));
        var originalRef = new ArtifactRef(
                approvedPlanning.artifactId(), approvedPlanning.artifactType(), approvedPlanning.version(),
                approvedPlanning.contentHash(), approvedPlanning.rootArtifactId(),
                approvedPlanning.parentArtifactId(), approvedPlanning.supersedesArtifactId(),
                ArtifactStatus.PROPOSED);
        when(artifacts.calculateHash(request)).thenReturn("same-command-hash");
        when(repository.findTransition(request.transitionId())).thenReturn(Optional.of(original));
        when(artifacts.require(approvedPlanning.artifactId())).thenReturn(approvedPlanning);
        when(artifacts.requireExact(originalRef)).thenReturn(approvedPlanning);
        when(artifacts.calculateHash(any(ArtifactTransitionCommand.AppendArtifactEvidence.class)))
                .thenReturn("evidence-hash");
        when(events.append(any())).thenReturn(event(DomainEventType.CodingPlanProposed));
        var evidence = new ArtifactEvidenceRequest(
                "evidence-plan-propose-1", null, ArtifactEvidenceType.PlanningExecution,
                request.transitionId(), objectMapper.valueToTree(Map.of("sessionId", "session-1")));

        var result = service.ingest(
                metadata(DomainEventType.CodingPlanProposed), Map.of(), request, evidence);

        assertThat(result.artifactRef().status()).isEqualTo(ArtifactStatus.PROPOSED);
        assertThat(result.artifactRef().artifactId()).isEqualTo(approvedPlanning.artifactId());
    }

    @Test
    void validationFailureCannotBypassTheRejectTransition() {
        var coding = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.PROPOSED,
                "art-product-1", "art-plan-1", null);
        var evidence = new ArtifactEvidenceRequest(
                "evidence-validation-1", ArtifactRef.from(coding), ArtifactEvidenceType.ValidationFailed,
                null, objectMapper.valueToTree(Map.of("failedCommand", "pytest")));

        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.ValidationFailed), Map.of("failedCommand", "pytest"),
                null, evidence))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Artifact Transition");
    }

    @Test
    void evidenceTypeAndTransitionLinkMustMatchTheDomainEvent() {
        var coding = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.PROPOSED,
                "art-product-1", "art-plan-1", null);
        var wrongType = new ArtifactEvidenceRequest(
                "evidence-wrong-type", ArtifactRef.from(coding), ArtifactEvidenceType.ValidationPassed,
                null, objectMapper.createObjectNode());

        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.WorkerBlocked), Map.of(), null, wrongType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能由事件 WorkerBlocked");

        var transition = new ArtifactTransitionRequest(
                "RejectCodingArtifact", "transition-reject-1", ArtifactRef.from(coding),
                null, null, null, null, "验证失败");
        var wrongLink = new ArtifactEvidenceRequest(
                "evidence-wrong-link", ArtifactRef.from(coding), ArtifactEvidenceType.ValidationFailed,
                "transition-other", objectMapper.createObjectNode());
        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.ValidationFailed), Map.of(), transition, wrongLink))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("transitionId 不一致");
    }

    @Test
    void codingContentRejectsLegacyEnvelopeFields() {
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.APPROVED,
                "art-product-1", "art-product-1", null);
        var content = objectMapper.valueToTree(Map.of(
                "summary", "完成实现",
                "repoChanges", List.of(),
                "executionOutcome", Map.of("status", "completed", "blockers", List.of()),
                "baseRevisions", Map.of(),
                "planningArtifactId", planning.artifactId()));
        var transition = new ArtifactTransitionRequest(
                "ProposeCodingArtifact", "transition-code-legacy", null,
                ArtifactRef.from(planning), null, null, content, "");
        when(repository.findTransition(transition.transitionId())).thenReturn(Optional.empty());
        when(artifacts.calculateHash(transition)).thenReturn("transition-hash");

        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.ModificationCompleted), Map.of(), transition,
                new ArtifactEvidenceRequest(
                        "evidence-code-legacy", null, ArtifactEvidenceType.CodingExecution,
                        transition.transitionId(), objectMapper.createObjectNode())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planningArtifactId");
    }

    @Test
    void planningAndCodingProposalsRequireExecutionEvidence() {
        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.CodingPlanProposed), Map.of(), mockTransition(
                        "ProposePlanningArtifact", "transition-plan"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Artifact Evidence");
        assertThatThrownBy(() -> service.ingest(
                metadata(DomainEventType.ModificationCompleted), Map.of(), mockTransition(
                        "ProposeCodingArtifact", "transition-code"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Artifact Evidence");
    }

    private ArtifactTransitionRequest mockTransition(String kind, String transitionId) {
        return new ArtifactTransitionRequest(
                kind, transitionId, null, null, null, null, objectMapper.createObjectNode(), "");
    }

    private ArtifactTransitionService.EventMetadata metadata(DomainEventType type) {
        return new ArtifactTransitionService.EventMetadata(
                type, "sys-1", "case-1", "prd-1", "wi-1", "worker", "worker",
                "case-1", "signal-1", "event:" + type);
    }

    private DomainEventRecord event(DomainEventType type) {
        return new DomainEventRecord(
                1L, "event-1", type.name(), "v5.0", "sys-1", "case-1", "prd-1",
                "wi-1", "worker", "worker", "{}", "case-1", "signal-1",
                "event:" + type, Instant.parse("2026-07-29T00:00:00Z"));
    }

    private Artifact artifact(
            String id, ArtifactType type, ArtifactStatus status, String root,
            String parent, String supersedes) {
        return new Artifact(
                id, type, root, "sys-1", "prd-1", "wi-1", "case-1", 1, status,
                parent, supersedes, null, objectMapper.createObjectNode(), "hash-" + id, "key-" + id,
                "worker", Instant.parse("2026-07-29T00:00:00Z"), null, null, null);
    }
}
