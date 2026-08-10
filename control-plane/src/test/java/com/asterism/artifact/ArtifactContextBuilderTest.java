package com.asterism.artifact;

import com.asterism.context.RequirementContextManifestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactContextBuilderTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final RequirementContextManifestService manifests = mock(RequirementContextManifestService.class);
    // 与 Spring Boot 运行时保持一致，支持 Snapshot 中的时间字段。
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ArtifactContextBuilder builder = new ArtifactContextBuilder(artifacts, manifests, objectMapper);

    @Test
    void codingContextLoadsOnlyApprovedArtifactChainAndPreviousCodingResult() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, "art-product-1", null,
                objectMapper.valueToTree(new ProductArtifactContent(
                        "标题", "目标", "范围", List.of("验收"), List.of(),
                        Map.of(), "manifest-1", List.of())));
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.APPROVED, product.rootArtifactId(),
                product.artifactId(),
                objectMapper.valueToTree(new PlanningArtifactContent(
                        "# 计划", Map.of("main", "abc"), List.of("AC-1"), List.of("main"),
                        List.of(), List.of(), List.of())));
        var coding = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.REJECTED, product.rootArtifactId(),
                planning.artifactId(),
                objectMapper.valueToTree(new CodingArtifactContent(
                        "上一版结果",
                        List.of(new CodingArtifactContent.RepoChange(
                                "main", "diff --git a/a b/a", List.of("a"), "摘要")),
                        new CodingArtifactContent.ExecutionOutcome(
                                CodingArtifactContent.ExecutionStatus.completed, List.of()),
                        Map.of("main", "abc"))));
        var productRef = ArtifactRef.from(product);
        var planningRef = ArtifactRef.from(planning);
        var codingRef = ArtifactRef.from(coding);
        when(artifacts.requireEffectiveApproved(productRef)).thenReturn(product);
        when(artifacts.requireEffectiveApproved(planningRef)).thenReturn(planning);
        when(artifacts.requireExact(codingRef)).thenReturn(coding);
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(
                Map.of(ArtifactType.PRODUCT, productRef, ArtifactType.PLANNING, planningRef));
        when(artifacts.transitions(coding.artifactId())).thenReturn(List.of(
                new ArtifactTransition(
                        "transition-reject-1", coding.artifactId(), ArtifactStatus.PROPOSED,
                        ArtifactStatus.REJECTED, "owner", "只修订登录提示", "event-1",
                        "command-hash", Instant.parse("2026-07-29T00:01:00Z"))));
        when(artifacts.evidence(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(artifacts.evidence(coding.artifactId())).thenReturn(List.of(
                new ArtifactEvidence(
                        "evidence-rework-1", coding.artifactId(), ArtifactEvidenceType.ReworkStarted.name(),
                        objectMapper.valueToTree(Map.of("note", "补充回归登录测试")),
                        null, "event-2", "owner", "evidence-hash-1",
                        Instant.parse("2026-07-29T00:02:00Z")),
                new ArtifactEvidence(
                        "evidence-blocked-1", coding.artifactId(), ArtifactEvidenceType.WorkerBlocked.name(),
                        objectMapper.valueToTree(Map.of("reason", "coding_attempt_failed")),
                        null, "event-3", "worker", "evidence-hash-2",
                        Instant.parse("2026-07-29T00:03:00Z"))));
        when(artifacts.calculateHash(org.mockito.ArgumentMatchers.any()))
                .thenReturn("0123456789abcdef0123456789abcdef");
        when(manifests.executionSnapshot(
                "sys-1", "prd-1", "wi-1", "manifest-1", "目标",
                objectMapper.convertValue(product.content(), Map.class)))
                .thenReturn(new RequirementContextManifestService.ExecutionContextSnapshot(
                        "sys-1", "manifest-1", List.of(), "bundle-1", List.of(), List.of()));

        var snapshot = builder.build(new ArtifactContextBuilder.Request(
                "sys-1", "prd-1", "wi-1", "manifest-1", "coding",
                productRef, planningRef, codingRef, Map.of("main", "abc")));

        assertThat(snapshot.productArtifact()).isEqualTo(productRef);
        assertThat(snapshot.planningArtifact()).isEqualTo(planningRef);
        assertThat(snapshot.previousArtifact()).isEqualTo(codingRef);
        assertThat(snapshot.previousContent().path("summary").asText()).isEqualTo("上一版结果");
        assertThat(snapshot.feedbackNotes()).containsExactly("只修订登录提示", "补充回归登录测试");
        var semantic = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(artifacts).calculateHash(semantic.capture());
        assertThat(semantic.getValue()).isInstanceOfSatisfying(
                Map.class,
                value -> assertThat(value).doesNotContainKey("executionBundleId"));
        assertThat(objectMapper.valueToTree(snapshot).toString())
                .doesNotContain("sessionTranscript", "hiddenThought", "tokenUsage");
    }

    @Test
    void codingContextRejectsPlanningArtifactThatIsNotApproved() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, "art-product-1", null,
                objectMapper.valueToTree(new ProductArtifactContent(
                        "标题", "目标", "范围", List.of(), List.of(),
                        Map.of(), "manifest-1", List.of())));
        var rejectedPlan = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.REJECTED, product.rootArtifactId(),
                product.artifactId(), objectMapper.createObjectNode());
        var productRef = ArtifactRef.from(product);
        var rejectedRef = ArtifactRef.from(rejectedPlan);
        when(artifacts.requireEffectiveApproved(productRef)).thenReturn(product);
        when(artifacts.requireEffectiveApproved(rejectedRef))
                .thenThrow(new ArtifactConflictException("Artifact 不是当前有效 Approved Head"));

        assertThatThrownBy(() -> builder.build(new ArtifactContextBuilder.Request(
                "sys-1", "prd-1", "wi-1", "manifest-1", "coding",
                productRef, rejectedRef, null, Map.of())))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("有效 Approved");
    }

    @Test
    void planningRefreshKeepsPreviousPlanningFromTheSameRoot() {
        var product = artifact(
                "art-product-2", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, "art-product-1", null,
                objectMapper.valueToTree(new ProductArtifactContent(
                        "标题 v2", "目标", "范围", List.of(), List.of(),
                        Map.of(), "manifest-2", List.of())));
        var previousPlanning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.SUPERSEDED,
                product.rootArtifactId(), "art-product-1",
                objectMapper.valueToTree(new PlanningArtifactContent(
                        "# 旧计划", Map.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of())));
        var productRef = ArtifactRef.from(product);
        var previousRef = ArtifactRef.from(previousPlanning);
        when(artifacts.requireEffectiveApproved(productRef)).thenReturn(product);
        when(artifacts.requireExact(previousRef)).thenReturn(previousPlanning);
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(
                Map.of(ArtifactType.PRODUCT, productRef));
        when(artifacts.transitions(previousPlanning.artifactId())).thenReturn(List.of());
        when(artifacts.evidence(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(artifacts.calculateHash(org.mockito.ArgumentMatchers.any()))
                .thenReturn("0123456789abcdef0123456789abcdef");
        when(manifests.executionSnapshot(
                "sys-1", "prd-1", "wi-1", "manifest-2", "目标",
                objectMapper.convertValue(product.content(), Map.class)))
                .thenReturn(new RequirementContextManifestService.ExecutionContextSnapshot(
                        "sys-1", "manifest-2", List.of(), "bundle-2", List.of(), List.of()));

        var snapshot = builder.build(new ArtifactContextBuilder.Request(
                "sys-1", "prd-1", "wi-1", "manifest-2", "planning",
                productRef, null, previousRef, Map.of()));

        assertThat(snapshot.previousArtifact()).isEqualTo(previousRef);
        assertThat(snapshot.previousContent().path("planMarkdown").asText()).isEqualTo("# 旧计划");
    }

    @Test
    void planningContextUsesEmptyObjectsBeforePlanningArtifactExists() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, "art-product-1", null,
                objectMapper.valueToTree(new ProductArtifactContent(
                        "标题", "目标", "范围", List.of(), List.of(),
                        Map.of(), "manifest-1", List.of())));
        var productRef = ArtifactRef.from(product);
        when(artifacts.requireEffectiveApproved(productRef)).thenReturn(product);
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(
                Map.of(ArtifactType.PRODUCT, productRef));
        when(artifacts.evidence(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(artifacts.calculateHash(org.mockito.ArgumentMatchers.any()))
                .thenReturn("0123456789abcdef0123456789abcdef");
        when(manifests.executionSnapshot(
                "sys-1", "prd-1", "wi-1", "manifest-1", "planning", "目标",
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new RequirementContextManifestService.ExecutionContextSnapshot(
                        "sys-1", "manifest-1", List.of(), "bundle-1", List.of(), List.of()));

        var snapshot = builder.build(new ArtifactContextBuilder.Request(
                "sys-1", "prd-1", "wi-1", "manifest-1", "planning",
                productRef, null, null, Map.of()));

        assertThat(snapshot.planningArtifact()).isNull();
        assertThat(snapshot.planningContent().isObject()).isTrue();
        assertThat(snapshot.planningContent().size()).isZero();
        assertThat(snapshot.previousArtifact()).isNull();
        assertThat(snapshot.previousContent().isObject()).isTrue();
        assertThat(snapshot.previousContent().size()).isZero();
    }

    private Artifact artifact(
            String id, ArtifactType type, ArtifactStatus status, String rootId,
            String parentId, com.fasterxml.jackson.databind.JsonNode content) {
        return new Artifact(
                id, type, rootId, "sys-1", "prd-1", "wi-1", "case-1", 1, status,
                parentId, null, null, content, "hash", "key-" + id,
                "worker", Instant.parse("2026-07-29T00:00:00Z"), null, null, null);
    }
}
