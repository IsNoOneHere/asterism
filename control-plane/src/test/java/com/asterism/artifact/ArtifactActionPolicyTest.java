package com.asterism.artifact;

import com.asterism.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactActionPolicyTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ArtifactActionPolicy policy = new ArtifactActionPolicy(artifacts);

    @Test
    void actionUsesTheExactArtifactDisplayedByTheWorkbench() {
        var artifact = coding(ArtifactStatus.PROPOSED);
        var reference = ArtifactRef.from(artifact);
        when(artifacts.requireEligibleProposal(reference)).thenReturn(artifact);

        assertThat(policy.validate(
                "patch_apply_approved", "wi-1", reference, Map.of(ArtifactType.CODING, reference)))
                .isEqualTo(reference);
    }

    @Test
    void codeReviewAcceptsTheEffectiveCodingVersionSelectedByTheOwner() {
        var artifact = coding(ArtifactStatus.APPROVED);
        var reference = ArtifactRef.from(artifact);
        when(artifacts.requireEffectiveApproved(reference)).thenReturn(artifact);

        assertThat(policy.validate("patch_apply_approved", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
        assertThat(policy.validate("patch_apply_rejected", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void missingOrStaleArtifactReferenceReturnsConflict() {
        assertThatThrownBy(() -> policy.validate("patch_apply_approved", "wi-1", null, Map.of()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("ARTIFACT_REF_REQUIRED");
        });

        var stale = ArtifactRef.from(coding(ArtifactStatus.PROPOSED));
        when(artifacts.requireEligibleProposal(stale)).thenThrow(new IllegalArgumentException("Artifact 不存在"));
        assertThatThrownBy(() -> policy.validate(
                "patch_apply_approved", "wi-1", stale, Map.of(ArtifactType.CODING, stale)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("STALE_ARTIFACT");
                });
    }

    @Test
    void invalidApprovedParentChainBlocksProposalAction() {
        var reference = ArtifactRef.from(coding(ArtifactStatus.PROPOSED));
        when(artifacts.requireEligibleProposal(reference))
                .thenThrow(new ArtifactConflictException("Artifact Proposal 的 Approved 父链已失效"));

        assertThatThrownBy(() -> policy.validate(
                "patch_apply_approved", "wi-1", reference, Map.of(ArtifactType.CODING, reference)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("STALE_ARTIFACT");
                });
    }

    @Test
    void contextRefreshUsesTheExactEffectiveProductHead() {
        var product = new Artifact(
                "art-product-1", ArtifactType.PRODUCT, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                null, null, null, new ObjectMapper().createObjectNode(),
                "hash-product-1", "transition-product-1", "owner",
                Instant.parse("2026-07-29T00:00:00Z"), "owner",
                Instant.parse("2026-07-29T00:00:00Z"), "PRD 已确认");
        var reference = ArtifactRef.from(product);
        when(artifacts.requireEffectiveApproved(reference)).thenReturn(product);

        assertThat(policy.validate("rework_with_latest_context", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void oldPageCannotReviewAnotherStillProposedArtifact() {
        var displayed = ArtifactRef.from(coding(ArtifactStatus.PROPOSED));
        var current = new ArtifactRef(
                "art-code-2", ArtifactType.CODING, 2, "hash-code-2",
                "art-product-1", "art-plan-1", null, ArtifactStatus.PROPOSED);

        assertThatThrownBy(() -> policy.validate(
                "patch_apply_approved", "wi-1", displayed, Map.of(ArtifactType.CODING, current)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("STALE_ARTIFACT");
                });
    }

    @Test
    void legacyEventWithoutArtifactRefUsesTheOnlyCurrentProposal() {
        var artifact = coding(ArtifactStatus.PROPOSED);
        var reference = ArtifactRef.from(artifact);
        when(artifacts.findArtifactChain("wi-1")).thenReturn(List.of(artifact));
        when(artifacts.requireEligibleProposal(reference)).thenReturn(artifact);

        assertThat(policy.validate("patch_apply_rejected", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void legacyReleaseWithoutValidationHeadKeepsCodingContract() {
        var artifact = coding(ArtifactStatus.APPROVED);
        var reference = ArtifactRef.from(artifact);
        when(artifacts.effectiveHeads(reference.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.CODING, reference));
        when(artifacts.requireEffectiveApproved(reference)).thenReturn(artifact);

        assertThat(policy.validate("release_approved", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void newReleaseRequiresTheCurrentPassedValidationArtifact() {
        var validation = validation();
        var reference = ArtifactRef.from(validation);
        when(artifacts.effectiveHeads(reference.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.VALIDATION, reference));
        when(artifacts.requirePassedValidation(reference)).thenReturn(validation);

        assertThat(policy.validate("release_approved", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void validationReworkUsesTheCurrentValidationArtifactForNewCases() {
        var validation = validation();
        var reference = ArtifactRef.from(validation);
        when(artifacts.effectiveHeads(reference.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.VALIDATION, reference));
        when(artifacts.requireEffectiveApproved(reference)).thenReturn(validation);

        assertThat(policy.validate("validation_rework_coding", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
        assertThat(policy.validate("validation_rework_planning", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    @Test
    void validationReworkUsesTheCurrentCodingArtifactForPreV22Cases() {
        var coding = coding(ArtifactStatus.APPROVED);
        var reference = ArtifactRef.from(coding);
        when(artifacts.effectiveHeads(reference.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.CODING, reference));
        when(artifacts.requireEffectiveApproved(reference)).thenReturn(coding);

        assertThat(policy.validate("validation_rework_coding", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
        assertThat(policy.validate("validation_rework_planning", "wi-1", reference, Map.of()))
                .isEqualTo(reference);
    }

    private Artifact coding(ArtifactStatus status) {
        return new Artifact(
                "art-code-1", ArtifactType.CODING, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, status,
                "art-plan-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-code-1", "transition-code-1", "worker",
                Instant.parse("2026-07-29T00:00:00Z"), null, null, null);
    }

    private Artifact validation() {
        return new Artifact(
                "art-validation-1", ArtifactType.VALIDATION, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-code-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-validation-1", "transition-validation-1", "worker",
                Instant.parse("2026-07-29T00:00:00Z"), "worker",
                Instant.parse("2026-07-29T00:00:00Z"), "验证结果 PASSED");
    }
}
