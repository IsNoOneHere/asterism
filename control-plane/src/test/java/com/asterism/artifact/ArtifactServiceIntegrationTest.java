package com.asterism.artifact;

import com.asterism.IntegrationDatabase;
import com.asterism.event.DomainEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ArtifactServiceIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired
    private ArtifactService artifacts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ArtifactTransitionService artifactTransitions;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsImmutableTypedChainAndPreservesRejectedVersions() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-1").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 v1"), "plan-1").artifact();
        var rejected1 = artifacts.reject(
                ArtifactRef.from(plan1), null, "owner", "补充验证步骤").artifact();
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(rejected1),
                null, planningContent("计划 v2"), "plan-2").artifact();
        var approved = artifacts.approve(
                ArtifactRef.from(plan2), null, "owner", "同意").artifact();

        assertThat(product.status()).isEqualTo(ArtifactStatus.APPROVED);
        assertThat(artifacts.require(plan1.artifactId()).status()).isEqualTo(ArtifactStatus.REJECTED);
        assertThat(approved.version()).isEqualTo(2);
        assertThat(approved.parentArtifactId()).isEqualTo(product.artifactId());
        assertThat(approved.supersedesArtifactId()).isEqualTo(plan1.artifactId());
        assertThat(approved.reviewedBy()).isEqualTo("owner");
        assertThat(artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(rejected1),
                null, planningContent("计划 v2"), "plan-2").artifact().artifactId())
                .isEqualTo(plan2.artifactId());

        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(product), null, null,
                codingContent(), "bad-code"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父节点必须是 PLANNING");

        assertThatThrownBy(() -> jdbc.sql("""
                        update artifacts set content_json = '{"planMarkdown":"覆盖"}'::jsonb
                        where artifact_id = :artifactId
                        """)
                .param("artifactId", plan2.artifactId())
                .update()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void allocatesDistinctVersionsUnderConcurrentRetriesAndKeepsHashStable() throws Exception {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-concurrent").artifact();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> artifacts.createProposal(
                    ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                    planningContent("计划 A"), "plan-a").artifact());
            var second = executor.submit(() -> artifacts.createProposal(
                    ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                    planningContent("计划 B"), "plan-b").artifact());
            assertThat(List.of(first.get().version(), second.get().version())).containsExactlyInAnyOrder(1, 2);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> artifacts.createProposal(
                    ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                    planningContent("幂等计划"), "same-plan").artifact());
            var second = executor.submit(() -> artifacts.createProposal(
                    ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                    planningContent("幂等计划"), "same-plan").artifact());
            assertThat(first.get().artifactId()).isEqualTo(second.get().artifactId());
        }
        assertThat(artifacts.calculateContentHash(planningContent("稳定计划")))
                .isEqualTo(artifacts.calculateContentHash(planningContent("稳定计划")));
    }

    @Test
    void concurrentReviewsDoNotOverwriteTheFirstDecision() throws Exception {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-review").artifact();
        var plan = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("并发审核计划"), "plan-review").artifact();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> {
                start.await();
                return artifacts.approve(ArtifactRef.from(plan), null, "approver", "批准");
            });
            var reject = executor.submit(() -> {
                start.await();
                return artifacts.reject(ArtifactRef.from(plan), null, "rejecter", "打回");
            });
            start.countDown();
            var successes = 0;
            for (var review : List.of(approve, reject)) {
                try {
                    review.get();
                    successes++;
                } catch (ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(ArtifactConflictException.class);
                }
            }
            assertThat(successes).isEqualTo(1);
        }

        var reviewed = artifacts.require(plan.artifactId());
        assertThat(reviewed.status()).isIn(ArtifactStatus.APPROVED, ArtifactStatus.REJECTED);
        assertThat(reviewed.reviewedBy()).isIn("approver", "rejecter");
    }

    @Test
    void concurrentProposalsCompeteForTheSameHeadWithCas() throws Exception {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-head-race").artifact();
        var first = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 A"), "plan-head-race-a").artifact();
        var second = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 B"), "plan-head-race-b").artifact();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstApproval = executor.submit(() -> {
                start.await();
                return artifacts.approve(ArtifactRef.from(first), null, "owner-a", "批准 A");
            });
            var secondApproval = executor.submit(() -> {
                start.await();
                return artifacts.approve(ArtifactRef.from(second), null, "owner-b", "批准 B");
            });
            start.countDown();
            var successes = 0;
            for (var approval : List.of(firstApproval, secondApproval)) {
                try {
                    approval.get();
                    successes++;
                } catch (ExecutionException error) {
                    assertThat(error.getCause()).isInstanceOf(ArtifactConflictException.class);
                }
            }
            assertThat(successes).isEqualTo(1);
        }

        var firstState = artifacts.require(first.artifactId());
        var secondState = artifacts.require(second.artifactId());
        assertThat(List.of(firstState.status(), secondState.status()))
                .containsExactlyInAnyOrder(ArtifactStatus.APPROVED, ArtifactStatus.PROPOSED);
        assertThat(artifacts.headRef(product.rootArtifactId(), ArtifactType.PLANNING).artifactId())
                .isIn(first.artifactId(), second.artifactId());
    }

    @Test
    void rejectedProposalKeepsOldHeadUntilANewerVersionIsApproved() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-head-lifecycle").artifact();
        var first = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 v1"), "plan-head-v1").artifact();
        var approvedFirst = artifacts.approve(
                ArtifactRef.from(first), null, "owner", "批准 v1").artifact();
        var rejectedProposal = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approvedFirst),
                ArtifactRef.from(approvedFirst), planningContent("计划 v2"), "plan-head-v2").artifact();
        artifacts.reject(
                ArtifactRef.from(rejectedProposal), ArtifactRef.from(approvedFirst), "owner", "继续使用 v1");

        assertThat(artifacts.require(approvedFirst.artifactId()).status()).isEqualTo(ArtifactStatus.APPROVED);
        assertThat(artifacts.headRef(product.rootArtifactId(), ArtifactType.PLANNING))
                .isEqualTo(ArtifactRef.from(approvedFirst));

        var next = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approvedFirst),
                ArtifactRef.from(approvedFirst), planningContent("计划 v3"), "plan-head-v3").artifact();
        var approvedNext = artifacts.approve(
                ArtifactRef.from(next), ArtifactRef.from(approvedFirst), "owner", "批准 v3").artifact();

        assertThat(artifacts.require(approvedFirst.artifactId()).status()).isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.headRef(product.rootArtifactId(), ArtifactType.PLANNING))
                .isEqualTo(ArtifactRef.from(approvedNext));
    }

    @Test
    void activatesRejectedPlanningVersionAndKeepsAllHistory() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-select-plan").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 v1"), "select-plan-v1").artifact();
        var rejectedPlan1 = artifacts.reject(
                ArtifactRef.from(plan1), null, "owner", "原计划暂不采用").artifact();
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(rejectedPlan1),
                null, planningContent("计划 v2"), "select-plan-v2").artifact();
        var approvedPlan2 = artifacts.approve(
                ArtifactRef.from(plan2), null, "owner", "批准 v2").artifact();
        var coding2 = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlan2), null, null,
                codingContent(), "select-coding-v2").artifact();
        var approvedCoding2 = artifacts.approve(
                ArtifactRef.from(coding2), null, "owner", "验证通过").artifact();
        var pendingPlan3 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approvedPlan2),
                ArtifactRef.from(approvedPlan2), planningContent("计划 v3"), "select-plan-v3").artifact();
        var expected = artifacts.effectiveHeads(product.rootArtifactId());

        var activation = artifacts.activateVersion(
                ArtifactRef.from(rejectedPlan1), expected, "owner");

        assertThat(activation.selectedArtifact().status()).isEqualTo(ArtifactStatus.APPROVED);
        assertThat(activation.effectiveHeads())
                .containsEntry(ArtifactType.PRODUCT, ArtifactRef.from(product))
                .containsEntry(ArtifactType.PLANNING, ArtifactRef.from(activation.selectedArtifact()))
                .doesNotContainKey(ArtifactType.CODING);
        assertThat(artifacts.require(approvedPlan2.artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.require(approvedCoding2.artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.require(pendingPlan3.artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.graph(metadata.workItemId()).nodes()).hasSize(5);
    }

    @Test
    void activatingCodingVersionRestoresItsWholeAncestorRoute() {
        var metadata = metadata();
        var product1 = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "route-product-v1").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product1), null, null,
                planningContent("路线计划 v1"), "route-plan-v1").artifact();
        var approvedPlan1 = artifacts.approve(
                ArtifactRef.from(plan1), null, "owner", "批准").artifact();
        var coding1 = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlan1), null, null,
                codingContent(), "route-coding-v1").artifact();
        var approvedCoding1 = artifacts.approve(
                ArtifactRef.from(coding1), null, "owner", "批准").artifact();

        var product2 = artifacts.createApprovedProduct(
                metadata,
                new ProductArtifactContent(
                        "Artifact Test v2", "验证新版路线", "Control Plane", List.of("可切回旧路线"),
                        List.of(), Map.of(), "manifest-2", List.of("PRDConfirmed:2")),
                ArtifactRef.from(product1), ArtifactRef.from(product1), "route-product-v2").artifact();
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product2), ArtifactRef.from(approvedPlan1),
                ArtifactRef.from(approvedPlan1), planningContent("路线计划 v2"), "route-plan-v2").artifact();
        var approvedPlan2 = artifacts.approve(
                ArtifactRef.from(plan2), ArtifactRef.from(approvedPlan1), "owner", "批准").artifact();
        var coding2 = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlan2), ArtifactRef.from(approvedCoding1),
                ArtifactRef.from(approvedCoding1), codingContent(), "route-coding-v2").artifact();
        artifacts.approve(
                ArtifactRef.from(coding2), ArtifactRef.from(approvedCoding1), "owner", "批准");

        var activation = artifacts.activateVersion(
                ArtifactRef.from(artifacts.require(approvedCoding1.artifactId())),
                artifacts.effectiveHeads(product1.rootArtifactId()), "owner");

        assertThat(activation.effectiveHeads().get(ArtifactType.PRODUCT).artifactId())
                .isEqualTo(product1.artifactId());
        assertThat(activation.effectiveHeads().get(ArtifactType.PLANNING).artifactId())
                .isEqualTo(plan1.artifactId());
        assertThat(activation.effectiveHeads().get(ArtifactType.CODING).artifactId())
                .isEqualTo(coding1.artifactId());
        assertThat(activation.effectiveHeads().values())
                .allMatch(ref -> ref.status() == ArtifactStatus.APPROVED);
    }

    @Test
    void versionActivationRejectsAStaleEffectiveRoute() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "stale-route-product").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("旧计划"), "stale-route-plan-v1").artifact();
        var approvedPlan1 = artifacts.approve(
                ArtifactRef.from(plan1), null, "owner", "批准").artifact();
        var staleHeads = artifacts.effectiveHeads(product.rootArtifactId());
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approvedPlan1),
                ArtifactRef.from(approvedPlan1), planningContent("新计划"), "stale-route-plan-v2").artifact();
        artifacts.approve(
                ArtifactRef.from(plan2), ArtifactRef.from(approvedPlan1), "owner", "批准");

        assertThatThrownBy(() -> artifacts.activateVersion(
                ArtifactRef.from(artifacts.require(approvedPlan1.artifactId())), staleHeads, "owner"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("有效版本已变化");
    }

    @Test
    void versionSelectionWritesAppendOnlyAuditAndIsIdempotent() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "audit-select-product").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("审计计划 v1"), "audit-select-plan-v1").artifact();
        var rejectedPlan1 = artifacts.reject(
                ArtifactRef.from(plan1), null, "owner", "先使用新计划").artifact();
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(rejectedPlan1),
                null, planningContent("审计计划 v2"), "audit-select-plan-v2").artifact();
        artifacts.approve(ArtifactRef.from(plan2), null, "owner", "批准 v2");
        var expected = artifacts.effectiveHeads(product.rootArtifactId());
        var transitionId = "transition-select-version-" + metadata.workItemId();
        var event = eventMetadata(DomainEventType.ArtifactVersionSelected, metadata, "select-version");

        var first = artifactTransitions.selectVersion(
                event, ArtifactRef.from(rejectedPlan1), expected, transitionId);
        var replay = artifactTransitions.selectVersion(
                event, ArtifactRef.from(rejectedPlan1), expected, transitionId);

        assertThat(first.selectedArtifact()).isEqualTo(replay.selectedArtifact());
        assertThat(artifacts.transitions(rejectedPlan1.artifactId()))
                .anySatisfy(transition -> {
                    assertThat(transition.fromStatus()).isEqualTo(ArtifactStatus.REJECTED);
                    assertThat(transition.toStatus()).isEqualTo(ArtifactStatus.APPROVED);
                });
        assertThat(jdbc.sql("""
                        select count(*) from domain_events
                        where work_item_id = :workItemId and event_type = 'ArtifactVersionSelected'
                        """)
                .param("workItemId", metadata.workItemId())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void proposalCannotBeReviewedAgainstADifferentHeadThanItWasCreatedWith() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-proposal-cas").artifact();
        var first = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 v1"), "proposal-cas-v1").artifact();
        var firstHead = artifacts.approve(
                ArtifactRef.from(first), null, "owner", "批准 v1").artifact();
        var staleProposal = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(firstHead),
                ArtifactRef.from(firstHead), planningContent("旧候选"), "proposal-cas-stale").artifact();
        var competing = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(firstHead),
                ArtifactRef.from(firstHead), planningContent("新候选"), "proposal-cas-current").artifact();
        var currentHead = artifacts.approve(
                ArtifactRef.from(competing), ArtifactRef.from(firstHead), "owner", "批准新候选").artifact();

        assertThatThrownBy(() -> artifacts.approve(
                ArtifactRef.from(staleProposal), ArtifactRef.from(currentHead), "owner", "错误换基线批准"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("expectedHead");
    }

    @Test
    void newProposalExplicitlySupersedesThePreviousProposedArtifact() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-proposal-replacement").artifact();
        var first = artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanProposed, metadata, "proposal-replacement-v1"),
                Map.of("planRevision", 1),
                new ArtifactTransitionRequest(
                        "ProposePlanningArtifact", "transition-proposal-replacement-v1-" + metadata.workItemId(),
                        null, ArtifactRef.from(product), null, null,
                        objectMapper.valueToTree(planningContent("计划 v1")), ""),
                executionEvidence(
                        ArtifactEvidenceType.PlanningExecution,
                        "transition-proposal-replacement-v1-" + metadata.workItemId()));
        var second = artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanProposed, metadata, "proposal-replacement-v2"),
                Map.of("planRevision", 2),
                new ArtifactTransitionRequest(
                        "ProposePlanningArtifact", "transition-proposal-replacement-v2-" + metadata.workItemId(),
                        null, ArtifactRef.from(product), first.artifactRef(), null,
                        objectMapper.valueToTree(planningContent("计划 v2")), ""),
                executionEvidence(
                        ArtifactEvidenceType.PlanningExecution,
                        "transition-proposal-replacement-v2-" + metadata.workItemId()));

        assertThat(artifacts.require(first.artifactRef().artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(second.artifactRef().status()).isEqualTo(ArtifactStatus.PROPOSED);
        assertThat(artifacts.transitions(first.artifactRef().artifactId()))
                .extracting(ArtifactTransition::toStatus)
                .containsExactly(ArtifactStatus.PROPOSED, ArtifactStatus.SUPERSEDED);
    }

    @Test
    void staleReferencesInvalidParentsAndChangedIdempotencyInputsAreRejected() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-stale").artifact();
        var plan = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("待审核计划"), "plan-stale").artifact();
        var reference = ArtifactRef.from(plan);

        assertThatThrownBy(() -> artifacts.approve(new ArtifactRef(
                "art-missing", reference.artifactType(), reference.version(),
                reference.contentHash(), reference.rootArtifactId(), reference.parentArtifactId(),
                reference.supersedesArtifactId(), reference.status()), null, "owner", "批准"))
                .isInstanceOf(ArtifactConflictException.class);
        assertThatThrownBy(() -> artifacts.approve(new ArtifactRef(
                reference.artifactId(), reference.artifactType(), reference.version() + 1,
                reference.contentHash(), reference.rootArtifactId(), reference.parentArtifactId(),
                reference.supersedesArtifactId(), reference.status()), null, "owner", "批准"))
                .isInstanceOf(ArtifactConflictException.class);
        assertThatThrownBy(() -> artifacts.approve(new ArtifactRef(
                reference.artifactId(), reference.artifactType(), reference.version(),
                "stale-content-hash", reference.rootArtifactId(), reference.parentArtifactId(),
                reference.supersedesArtifactId(), reference.status()), null, "owner", "批准"))
                .isInstanceOf(ArtifactConflictException.class);

        var approved = artifacts.approve(reference, null, "owner", "批准").artifact();
        artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approved),
                ArtifactRef.from(approved), planningContent("幂等计划"), "plan-idempotent");
        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approved),
                null, planningContent("幂等计划"), "plan-idempotent"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("expectedHead");
        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(approved),
                ArtifactRef.from(approved), planningContent("不同内容"), "plan-idempotent"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("Content");

        var product2 = artifacts.createApprovedProduct(
                metadata,
                new ProductArtifactContent(
                        "Artifact Test v2", "验证父链失效", "Control Plane", List.of("旧计划不能批准"),
                        List.of(), Map.of(), "manifest-2", List.of("PRDConfirmed:2")),
                ArtifactRef.from(product), ArtifactRef.from(product), "product-stale-v2").artifact();
        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product2), ArtifactRef.from(approved),
                ArtifactRef.from(approved), planningContent("幂等计划"), "plan-idempotent"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("parent");

        var staleChild = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product2), ArtifactRef.from(approved),
                ArtifactRef.from(approved), planningContent("新版父链计划"), "stale-child").artifact();
        var product3 = artifacts.createApprovedProduct(
                metadata,
                new ProductArtifactContent(
                        "Artifact Test v3", "再次更新需求", "Control Plane", List.of("子产物失效"),
                        List.of(), Map.of(), "manifest-3", List.of("PRDConfirmed:3")),
                ArtifactRef.from(product2), ArtifactRef.from(product2), "product-stale-v3").artifact();
        assertThat(product3.status()).isEqualTo(ArtifactStatus.APPROVED);
        assertThatThrownBy(() -> artifacts.approve(
                ArtifactRef.from(staleChild), ArtifactRef.from(approved), "owner", "错误批准"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("有效 Approved");
    }

    @Test
    void graphContainsDerivedFromAndSupersedesAxes() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-graph").artifact();
        var first = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("计划 v1"), "graph-plan-v1").artifact();
        var rejected = artifacts.reject(ArtifactRef.from(first), null, "owner", "更新计划").artifact();
        var second = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), ArtifactRef.from(rejected), null,
                planningContent("计划 v2"), "graph-plan-v2").artifact();
        var approved = artifacts.approve(ArtifactRef.from(second), null, "owner", "批准").artifact();
        var coding = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approved), null, null,
                codingContent(), "graph-coding").artifact();

        var graph = artifacts.graph(metadata.workItemId());
        assertThat(graph.edges()).contains(
                new ArtifactGraph.Edge(product.artifactId(), first.artifactId(), ArtifactGraph.EdgeType.DERIVED_FROM),
                new ArtifactGraph.Edge(product.artifactId(), second.artifactId(), ArtifactGraph.EdgeType.DERIVED_FROM),
                new ArtifactGraph.Edge(first.artifactId(), second.artifactId(), ArtifactGraph.EdgeType.SUPERSEDES),
                new ArtifactGraph.Edge(approved.artifactId(), coding.artifactId(), ArtifactGraph.EdgeType.DERIVED_FROM));
        assertThat(graph.effectiveHeads()).containsEntry(ArtifactType.PLANNING, ArtifactRef.from(approved));
    }

    @Test
    void codingArtifactQueriesValidationCommitAndMergeRequestEvidence() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-evidence").artifact();
        var planning = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("证据计划"), "plan-evidence").artifact();
        var approvedPlanning = artifacts.approve(
                ArtifactRef.from(planning), null, "owner", "批准").artifact();
        var coding = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlanning), null, null,
                codingContent(), "coding-evidence").artifact();

        var validation = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ValidationPassed, metadata, "validation"),
                Map.of("commands", List.of(Map.of("command", "pytest", "exitCode", 0))),
                new ArtifactTransitionRequest(
                        "ApproveCodingArtifact", "transition-validation-" + metadata.workItemId(),
                        ArtifactRef.from(coding), null, null, null, null, "验证通过"),
                new ArtifactEvidenceRequest(
                        "evidence-validation-" + metadata.workItemId(), ArtifactRef.from(coding),
                        ArtifactEvidenceType.ValidationPassed, "transition-validation-" + metadata.workItemId(),
                        objectMapper.valueToTree(Map.of("command", "pytest", "exitCode", 0))));
        var approvedCoding = artifacts.require(validation.artifactRef().artifactId());

        artifactTransitions.ingest(
                eventMetadata(DomainEventType.RepositoryReleasePrepared, metadata, "commit"),
                Map.of("repo", "main", "commitHash", "abc123"),
                null,
                new ArtifactEvidenceRequest(
                        "evidence-commit-" + metadata.workItemId(), ArtifactRef.from(approvedCoding),
                        ArtifactEvidenceType.Commit, null,
                        objectMapper.valueToTree(Map.of("repo", "main", "commitHash", "abc123"))));
        artifactTransitions.ingest(
                eventMetadata(DomainEventType.MergeRequestCreated, metadata, "mr"),
                Map.of("repo", "main", "mrIid", 12),
                null,
                new ArtifactEvidenceRequest(
                        "evidence-mr-" + metadata.workItemId(), ArtifactRef.from(approvedCoding),
                        ArtifactEvidenceType.MergeRequest, null,
                        objectMapper.valueToTree(Map.of("repo", "main", "mrIid", 12))));

        assertThat(artifacts.evidence(approvedCoding.artifactId()))
                .extracting(ArtifactEvidence::evidenceType)
                .containsExactlyInAnyOrder("ValidationPassed", "Commit", "MergeRequest");
        assertThat(artifacts.transitions(approvedCoding.artifactId()))
                .extracting(ArtifactTransition::toStatus)
                .contains(ArtifactStatus.APPROVED);
    }

    @Test
    void materializesValidationAndReleaseResultsWithTraceableVersionHistory() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "result-product").artifact();
        var planning = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("结果产物计划"), "result-planning").artifact();
        var approvedPlanning = artifacts.approve(
                ArtifactRef.from(planning), null, "owner", "批准").artifact();
        var coding = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlanning), null, null,
                codingContent(), "result-coding").artifact();
        var approvedCoding = artifacts.approve(
                ArtifactRef.from(coding), null, "owner", "Patch 已应用").artifact();

        var failedMetadata = eventMetadata(DomainEventType.ValidationFailed, metadata, "validation-failed");
        var failedPayload = Map.<String, Object>of(
                "artifactResultVersion", 1,
                "completedAt", "2026-08-05T00:00:00Z",
                "codingArtifactId", approvedCoding.artifactId(),
                "validationMode", "AUTO",
                "commands", List.of(Map.of("repo", "main", "command", "pytest", "exitCode", 1)),
                "stderrTail", "1 failed");
        var failed = artifactTransitions.ingest(
                failedMetadata, failedPayload, null,
                new ArtifactEvidenceRequest(
                        "result-validation-failed-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ValidationFailed, null, objectMapper.valueToTree(failedPayload)));

        assertThat(failed.artifactRef().artifactType()).isEqualTo(ArtifactType.VALIDATION);
        assertThat(failed.artifactRef().status()).isEqualTo(ArtifactStatus.APPROVED);
        assertThat(failed.evidence().artifactId()).isEqualTo(failed.artifactRef().artifactId());
        var driftedFailedPayload = new java.util.LinkedHashMap<>(failedPayload);
        driftedFailedPayload.put("completedAt", "2026-08-05T00:00:01Z");
        assertThatThrownBy(() -> artifactTransitions.ingest(
                failedMetadata, driftedFailedPayload, null,
                new ArtifactEvidenceRequest(
                        "result-validation-drift-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ValidationFailed, null,
                        objectMapper.valueToTree(driftedFailedPayload))))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("幂等键");
        assertThatThrownBy(() -> artifacts.createApprovedResult(
                ArtifactType.RELEASE, metadata, failed.artifactRef(), null, null,
                new ReleaseArtifactContent(
                        "release-blocked", "local", "default", List.of(),
                        ArtifactRef.from(approvedCoding), failed.artifactRef(),
                        java.time.Instant.parse("2026-08-05T00:01:00Z")),
                "release-blocked", "不应发布"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("PASSED");

        var passedPayload = Map.<String, Object>of(
                "artifactResultVersion", 1,
                "completedAt", "2026-08-05T00:02:00Z",
                "codingArtifactId", approvedCoding.artifactId(),
                "validationMode", "AUTO",
                "commands", List.of(Map.of("repo", "main", "command", "pytest", "exitCode", 0)));
        var passed = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ValidationPassed, metadata, "validation-passed"),
                passedPayload, null,
                new ArtifactEvidenceRequest(
                        "result-validation-passed-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ValidationPassed, null, objectMapper.valueToTree(passedPayload)));
        assertThat(passed.artifactRef().supersedesArtifactId())
                .isEqualTo(failed.artifactRef().artifactId());
        assertThat(artifacts.require(failed.artifactRef().artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);

        var releasePayload = Map.<String, Object>of(
                "artifactResultVersion", 1,
                "completedAt", "2026-08-05T00:03:00Z",
                "validationArtifactId", passed.artifactRef().artifactId(),
                "releaseMode", "local",
                "releaseId", "release-1",
                "targetKey", "default",
                "repositories", List.of(Map.of(
                        "repo", "main", "branch", "wi/test", "commitHash", "abc123",
                        "finalState", "completed", "changedPaths", List.of("src/App.java"))));
        var release = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ReleaseCompleted, metadata, "release-completed"),
                releasePayload, null,
                new ArtifactEvidenceRequest(
                        "result-release-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ReleaseCompleted, null, objectMapper.valueToTree(releasePayload)));

        assertThat(release.artifactRef().artifactType()).isEqualTo(ArtifactType.RELEASE);
        assertThat(release.artifactRef().parentArtifactId()).isEqualTo(passed.artifactRef().artifactId());
        assertThat(artifacts.effectiveHeads(product.rootArtifactId()))
                .containsEntry(ArtifactType.VALIDATION, passed.artifactRef())
                .containsEntry(ArtifactType.RELEASE, release.artifactRef());

        var codingV2 = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ModificationCompleted, metadata, "coding-v2-proposed"),
                Map.of("summary", "代码 v2 完成"),
                new ArtifactTransitionRequest(
                        "ProposeCodingArtifact", "transition-coding-v2-" + metadata.workItemId(),
                        null, ArtifactRef.from(approvedPlanning), ArtifactRef.from(approvedCoding),
                        ArtifactRef.from(approvedCoding), objectMapper.valueToTree(codingContent()), ""),
                executionEvidence(
                        ArtifactEvidenceType.CodingExecution,
                        "transition-coding-v2-" + metadata.workItemId()));
        var approvedCodingV2 = artifactTransitions.ingest(
                eventMetadata(DomainEventType.PatchApplied, metadata, "coding-v2-approved"),
                Map.of("summary", "代码 v2 Patch 已应用"),
                new ArtifactTransitionRequest(
                        "ApproveCodingArtifact", "transition-coding-v2-approve-" + metadata.workItemId(),
                        codingV2.artifactRef(), null, null, ArtifactRef.from(approvedCoding), null,
                        "批准代码 v2"),
                new ArtifactEvidenceRequest(
                        "evidence-coding-v2-approve-" + metadata.workItemId(), codingV2.artifactRef(),
                        ArtifactEvidenceType.PatchApplied,
                        "transition-coding-v2-approve-" + metadata.workItemId(),
                        objectMapper.valueToTree(Map.of("summary", "代码 v2 Patch 已应用"))));

        assertThat(artifacts.effectiveHeads(product.rootArtifactId()))
                .containsEntry(ArtifactType.CODING, approvedCodingV2.artifactRef())
                .doesNotContainKeys(ArtifactType.VALIDATION, ArtifactType.RELEASE);
        assertThat(artifacts.require(passed.artifactRef().artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.require(release.artifactRef().artifactId()).status())
                .isEqualTo(ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.transitions(passed.artifactRef().artifactId()))
                .anySatisfy(transition -> {
                    assertThat(transition.toStatus()).isEqualTo(ArtifactStatus.SUPERSEDED);
                    assertThat(transition.note()).contains("上游 CODING Head 已切换");
                });
        assertThat(artifacts.transitions(release.artifactRef().artifactId()))
                .anySatisfy(transition -> {
                    assertThat(transition.toStatus()).isEqualTo(ArtifactStatus.SUPERSEDED);
                    assertThat(transition.note()).contains("上游 CODING Head 已切换");
                });
    }

    @Test
    void explicitSkipValidationCanReleaseWithoutBeingReportedAsPassed() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "skip-product").artifact();
        var planning = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                planningContent("跳过验证计划"), "skip-planning").artifact();
        var approvedPlanning = artifacts.approve(
                ArtifactRef.from(planning), null, "owner", "批准").artifact();
        var coding = artifacts.createProposal(
                ArtifactType.CODING, metadata, ArtifactRef.from(approvedPlanning), null, null,
                codingContent(), "skip-coding").artifact();
        var approvedCoding = artifacts.approve(
                ArtifactRef.from(coding), null, "owner", "Patch 已应用").artifact();

        var skippedPayload = Map.<String, Object>of(
                "artifactResultVersion", 1,
                "completedAt", "2026-08-05T01:00:00Z",
                "codingArtifactId", approvedCoding.artifactId(),
                "validationMode", "SKIP",
                "skipped", true,
                "commands", List.of());
        var skipped = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ValidationPassed, metadata, "validation-skipped"),
                skippedPayload, null,
                new ArtifactEvidenceRequest(
                        "result-validation-skipped-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ValidationPassed, null,
                        objectMapper.valueToTree(skippedPayload)));
        var content = objectMapper.convertValue(
                artifacts.require(skipped.artifactRef().artifactId()).content(),
                ValidationArtifactContent.class);
        assertThat(content.mode()).isEqualTo(ValidationArtifactContent.Mode.SKIP);
        assertThat(content.result()).isEqualTo(ValidationArtifactContent.Result.SKIPPED);

        var releasePayload = Map.<String, Object>of(
                "artifactResultVersion", 1,
                "completedAt", "2026-08-05T01:01:00Z",
                "validationArtifactId", skipped.artifactRef().artifactId(),
                "releaseMode", "local",
                "releaseId", "skip-release",
                "targetKey", "default",
                "repositories", List.of());
        var release = artifactTransitions.ingest(
                eventMetadata(DomainEventType.ReleaseCompleted, metadata, "skip-release-completed"),
                releasePayload, null,
                new ArtifactEvidenceRequest(
                        "result-skip-release-evidence-" + metadata.workItemId(), null,
                        ArtifactEvidenceType.ReleaseCompleted, null,
                        objectMapper.valueToTree(releasePayload)));

        assertThat(release.artifactRef().artifactType()).isEqualTo(ArtifactType.RELEASE);
        assertThat(release.artifactRef().parentArtifactId()).isEqualTo(skipped.artifactRef().artifactId());
    }

    @Test
    void supersedingAHeadKeepsItsApprovalTransitionHistory() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-transition-history").artifact();
        var firstProposal = artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanProposed, metadata, "plan-v1-proposed"),
                Map.of("planRevision", 1),
                new ArtifactTransitionRequest(
                        "ProposePlanningArtifact", "transition-plan-v1-" + metadata.workItemId(),
                        null, ArtifactRef.from(product), null, null,
                        objectMapper.valueToTree(planningContent("计划 v1")), ""),
                executionEvidence(
                        ArtifactEvidenceType.PlanningExecution,
                        "transition-plan-v1-" + metadata.workItemId()));
        var firstApproval = artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanApproved, metadata, "plan-v1-approved"),
                Map.of("planRevision", 1),
                new ArtifactTransitionRequest(
                        "ApprovePlanningArtifact", "transition-plan-v1-approve-" + metadata.workItemId(),
                        firstProposal.artifactRef(), null, null, null, null, "批准 v1"),
                null);
        var secondProposal = artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanProposed, metadata, "plan-v2-proposed"),
                Map.of("planRevision", 2),
                new ArtifactTransitionRequest(
                        "ProposePlanningArtifact", "transition-plan-v2-" + metadata.workItemId(),
                        null, ArtifactRef.from(product), firstApproval.artifactRef(), firstApproval.artifactRef(),
                        objectMapper.valueToTree(planningContent("计划 v2")), ""),
                executionEvidence(
                        ArtifactEvidenceType.PlanningExecution,
                        "transition-plan-v2-" + metadata.workItemId()));
        artifactTransitions.ingest(
                eventMetadata(DomainEventType.CodingPlanApproved, metadata, "plan-v2-approved"),
                Map.of("planRevision", 2),
                new ArtifactTransitionRequest(
                        "ApprovePlanningArtifact", "transition-plan-v2-approve-" + metadata.workItemId(),
                        secondProposal.artifactRef(), null, null, firstApproval.artifactRef(),
                        null, "批准 v2"),
                null);

        assertThat(artifacts.transitions(firstApproval.artifactRef().artifactId()))
                .extracting(ArtifactTransition::toStatus)
                .containsExactlyInAnyOrder(
                        ArtifactStatus.PROPOSED, ArtifactStatus.APPROVED, ArtifactStatus.SUPERSEDED);
        assertThat(artifacts.transitions(firstApproval.artifactRef().artifactId()))
                .extracting(ArtifactTransition::note)
                .contains("批准 v1");
    }

    @Test
    void keepsSupersedesLineageWhenTheApprovedParentChanges() {
        var metadata = metadata();
        var product1 = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "product-v1").artifact();
        var plan1 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product1), null, null,
                planningContent("计划 v1"), "parent-plan-v1").artifact();
        var approvedPlan1 = artifacts.approve(
                ArtifactRef.from(plan1), null, "owner", "批准").artifact();

        var product2Content = new ProductArtifactContent(
                "Artifact Test", "验证新版产物链", "Control Plane", List.of("链路可追溯"),
                List.of(), Map.of(), "manifest-2", List.of("PRDConfirmed:2"));
        assertThatThrownBy(() -> artifacts.createApprovedProduct(
                metadata, product2Content, null, ArtifactRef.from(product1),
                "product-v2-without-supersedes"))
                .isInstanceOf(ArtifactConflictException.class)
                .hasMessageContaining("supersedes");
        var product2 = artifacts.createApprovedProduct(
                metadata, product2Content,
                ArtifactRef.from(product1), ArtifactRef.from(product1), "product-v2").artifact();
        var plan2 = artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product2),
                ArtifactRef.from(approvedPlan1), ArtifactRef.from(approvedPlan1),
                planningContent("计划 v2"), "parent-plan-v2").artifact();
        artifacts.approve(
                ArtifactRef.from(plan2), ArtifactRef.from(approvedPlan1), "owner", "批准新版计划");

        assertThat(product2.supersedesArtifactId()).isEqualTo(product1.artifactId());
        assertThat(plan2.parentArtifactId()).isEqualTo(product2.artifactId());
        assertThat(plan2.supersedesArtifactId()).isEqualTo(plan1.artifactId());
        assertThat(plan2.version()).isEqualTo(2);
        assertThat(artifacts.require(plan1.artifactId()).status()).isEqualTo(ArtifactStatus.SUPERSEDED);
    }

    @Test
    void rejectsUnsafeContentAndParentsFromAnotherWorkItem() {
        var metadata = metadata();
        var product = artifacts.createApprovedProduct(
                metadata, productContent(), null, null, "safe-product").artifact();
        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.PLANNING, metadata, ArtifactRef.from(product), null, null,
                new PlanningArtifactContent(
                        "authorization: bearer secret-value", Map.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                "unsafe-plan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密钥或 Token");

        var otherMetadata = metadata();
        assertThatThrownBy(() -> artifacts.createProposal(
                ArtifactType.PLANNING, otherMetadata, ArtifactRef.from(product), null, null,
                planningContent("跨工作项计划"), "cross-work-item-plan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于当前工作项");
    }

    private ArtifactService.Metadata metadata() {
        var suffix = UUID.randomUUID().toString();
        var systemId = "sys-" + suffix;
        var prdId = "prd-" + suffix;
        var workItemId = "wi-" + suffix;
        jdbc.sql("""
                        insert into systems(system_id, name, repo_path, owner_user_id)
                        values (:systemId, 'Artifact Test', '/tmp/repo', 'admin')
                        """)
                .param("systemId", systemId)
                .update();
        jdbc.sql("""
                        insert into prd_sessions(
                            prd_id, system_id, conversation_id, work_item_id, case_id,
                            title, goal, status, created_by)
                        values (:prdId, :systemId, :conversationId, :workItemId, :caseId,
                            'Artifact Test', '验证产物链', 'case_starting', 'admin')
                        """)
                .param("prdId", prdId)
                .param("systemId", systemId)
                .param("conversationId", "conv-" + suffix)
                .param("workItemId", workItemId)
                .param("caseId", "case-" + suffix)
                .update();
        return new ArtifactService.Metadata(systemId, prdId, workItemId, "case-" + suffix, "admin");
    }

    private ProductArtifactContent productContent() {
        return new ProductArtifactContent(
                "Artifact Test", "验证产物链", "Control Plane", List.of("链路可追溯"),
                List.of(), Map.of("AC-1", List.of("MSG:1")), "manifest-1", List.of("PRDConfirmed:1"));
    }

    private PlanningArtifactContent planningContent(String markdown) {
        return new PlanningArtifactContent(
                markdown, Map.of("main", "abc123"), List.of("AC-1"), List.of("main"),
                List.of("git:main@abc123"), List.of(), List.of());
    }

    private CodingArtifactContent codingContent() {
        return new CodingArtifactContent(
                "完成", List.of(new CodingArtifactContent.RepoChange(
                        "main",
                        "diff --git a/src/App.java b/src/App.java\n"
                                + "--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n",
                        List.of("src/App.java"), "完成代码修改")),
                new CodingArtifactContent.ExecutionOutcome(
                        CodingArtifactContent.ExecutionStatus.completed, List.of()),
                Map.of("main", "abc123"));
    }

    private ArtifactTransitionService.EventMetadata eventMetadata(
            DomainEventType type, ArtifactService.Metadata metadata, String suffix) {
        return new ArtifactTransitionService.EventMetadata(
                type, metadata.systemId(), metadata.caseId(), metadata.prdId(), metadata.workItemId(),
                "worker", "worker", metadata.workItemId(), suffix,
                "artifact-test:" + metadata.workItemId() + ":" + suffix);
    }

    private ArtifactEvidenceRequest executionEvidence(
            ArtifactEvidenceType type, String transitionId) {
        return new ArtifactEvidenceRequest(
                transitionId + ":evidence", null, type, transitionId,
                objectMapper.valueToTree(Map.of("sessionId", "integration-session")));
    }
}
