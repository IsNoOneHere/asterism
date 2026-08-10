package com.asterism.context;

import com.asterism.IntegrationDatabase;
import com.asterism.memory.MemoryApplicability;
import com.asterism.memory.MemoryCandidateService;
import com.asterism.memory.MemoryStatus;
import com.asterism.memory.MemoryType;
import com.asterism.prd.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ContextRecallIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired private JdbcClient jdbc;
    @Autowired private MemoryContextSource memories;
    @Autowired private ContextRecallService recall;
    @Autowired private RequirementContextManifestService manifests;
    @Autowired private MemoryCandidateService candidates;

    @Test
    void phaseRecallFiltersProjectTypeAndArtifactLineageBeforeSemanticRanking() {
        var fixture = fixture();
        var targetId = "target-" + fixture.suffix();
        knowledge(fixture.systemId(), targetId, "登录页", "/login/" + fixture.suffix());
        var fact = memory(
                fixture, fixture.productArtifactId(), MemoryType.FACT,
                MemoryCandidateService.ARTIFACT_APPROVED, "业务事实", "登录错误提示必须使用中文",
                MemoryApplicability.PROJECT, List.of());
        var decision = memory(
                fixture, fixture.planningArtifactId(), MemoryType.DECISION,
                MemoryCandidateService.ARTIFACT_APPROVED, "技术决策", "采用现有登录提示组件",
                MemoryApplicability.PROJECT, List.of());
        var constraint = memory(
                fixture, fixture.planningArtifactId(), MemoryType.CONSTRAINT,
                MemoryCandidateService.ARTIFACT_APPROVED, "实现约束", "禁止直接绕过统一鉴权",
                MemoryApplicability.PROJECT, List.of(targetId));
        var experience = memory(
                fixture, fixture.codingArtifactId(), MemoryType.EXPERIENCE,
                MemoryCandidateService.CODING_COMPLETED, "代码经验", "修改登录提示时需要同步移动端样式",
                MemoryApplicability.ARTIFACT_LINEAGE, List.of());
        var history = memory(
                fixture, fixture.codingArtifactId(), MemoryType.EXPERIENCE,
                MemoryCandidateService.CODING_COMPLETED, "历史经验", "登录提示曾遗漏无障碍文案同步",
                MemoryApplicability.PROJECT, List.of());

        assertThat(memories.recall(query(fixture, "product", List.of(), "登录提示")))
                .extracting(ContextItem::refId)
                .containsExactlyInAnyOrder("MEM:" + fact, "MEM:" + history);
        assertThat(memories.recall(query(
                fixture, "planning",
                List.of(fixture.productArtifactId(), fixture.planningArtifactId()), "登录提示")))
                .extracting(ContextItem::refId)
                .containsExactlyInAnyOrder(
                        "MEM:" + fact, "MEM:" + decision, "MEM:" + constraint);
        assertThat(memories.recall(query(
                fixture, "coding",
                List.of(fixture.productArtifactId(), fixture.planningArtifactId()), "登录提示")))
                .extracting(ContextItem::refId)
                .containsExactlyInAnyOrder("MEM:" + constraint, "MEM:" + history);
        assertThat(memories.recall(query(
                fixture, "coding",
                List.of(
                        fixture.productArtifactId(),
                        fixture.planningArtifactId(),
                        fixture.codingArtifactId()), "登录提示")))
                .extracting(ContextItem::refId)
                .containsExactlyInAnyOrder(
                        "MEM:" + constraint, "MEM:" + experience, "MEM:" + history);

        var ranked = recall.recall(query(
                fixture, "coding",
                List.of(
                        fixture.productArtifactId(),
                        fixture.planningArtifactId(),
                        fixture.codingArtifactId()), "完全无关"));
        assertThat(ranked.items()).isNotEmpty();
        assertThat(ranked.items().getFirst().refId()).isEqualTo("MEM:" + constraint);
        assertThat(ranked.items().getFirst().targetRefs()).containsExactly(targetId);
    }

    @Test
    void approvedReplacementKeepsOldKnowledgeButMarksItOutdated() {
        var fixture = fixture();
        var memoryId = memory(
                fixture, fixture.planningArtifactId(), MemoryType.DECISION,
                MemoryCandidateService.ARTIFACT_APPROVED, "搜索决策", "采用 Elasticsearch 支持订单检索",
                MemoryApplicability.PROJECT, List.of());
        var pending = candidates.create(new MemoryCandidateService.CandidateInput(
                fixture.systemId(), fixture.systemId(), MemoryType.CONSTRAINT, fixture.planningArtifactId(),
                MemoryCandidateService.ARTIFACT_APPROVED, "搜索约束", "禁止绕过统一订单权限",
                0.9, MemoryApplicability.PROJECT, null, List.of(), List.of("evt-plan-1"),
                "evt-plan-1", "memory-extractor"));
        var replacementId = "art-planning-v2-" + fixture.suffix();
        jdbc.sql("update artifacts set status = 'SUPERSEDED' where artifact_id = :artifactId")
                .param("artifactId", fixture.planningArtifactId())
                .update();
        artifact(
                fixture, replacementId, "PLANNING", 2, "APPROVED",
                fixture.productArtifactId(), fixture.planningArtifactId());

        candidates.refreshArtifactStatuses(fixture.productArtifactId());

        assertThat(jdbc.sql("select status from memory_items where memory_id = :memoryId")
                .param("memoryId", memoryId)
                .query(String.class)
                .single()).isEqualTo("OUTDATED");
        assertThat(jdbc.sql("select status from memory_candidates where candidate_id = :candidateId")
                .param("candidateId", pending.candidateId())
                .query(String.class)
                .single()).isEqualTo("OUTDATED");
        assertThat(jdbc.sql("select count(*) from memory_items where memory_id = :memoryId")
                .param("memoryId", memoryId)
                .query(Long.class)
                .single()).isOne();
        assertThat(memories.recall(query(
                fixture, "planning",
                List.of(fixture.productArtifactId(), replacementId), "订单检索")))
                .extracting(ContextItem::refId)
                .doesNotContain("MEM:" + memoryId);
    }

    @Test
    void candidateRequiresHumanConfirmationAndRejectsValidationPlaceholder() {
        var fixture = fixture();
        var candidate = candidates.create(new MemoryCandidateService.CandidateInput(
                fixture.systemId(), fixture.systemId(), MemoryType.FACT, fixture.productArtifactId(),
                MemoryCandidateService.ARTIFACT_APPROVED, "业务事实", "订单查询沿用项目编号",
                0.9, MemoryApplicability.PROJECT, null, List.of(), List.of("evt-product"),
                "evt-product", "memory-extractor"));

        assertThat(candidate.status().name()).isEqualTo("PENDING");
        assertThat(jdbc.sql("""
                        select count(*) from memory_items where candidate_id = :candidateId
                        """)
                .param("candidateId", candidate.candidateId())
                .query(Long.class)
                .single()).isZero();

        var approved = candidates.approve(candidate, new MemoryCandidateService.CandidateEdit(
                MemoryType.FACT, candidate.title(), candidate.content(), candidate.confidence(),
                candidate.applicability(), null, List.of()), "owner");

        assertThat(approved.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(approved.artifactSourceId()).isEqualTo(fixture.productArtifactId());

        artifact(
                fixture, fixture.failedCodingArtifactId(), "CODING", 2, "REJECTED",
                fixture.planningArtifactId(), fixture.codingArtifactId());
        var failed = candidates.create(new MemoryCandidateService.CandidateInput(
                fixture.systemId(), fixture.systemId(), MemoryType.EXPERIENCE,
                fixture.failedCodingArtifactId(), MemoryCandidateService.VALIDATION_FAILED,
                "问题经验", "验证问题：测试未通过。[待补充] 根因和解决方式。",
                0.7, MemoryApplicability.PROJECT, null, List.of(), List.of("evt-validation"),
                "evt-validation", "memory-extractor"));

        assertThatThrownBy(() -> candidates.approve(
                failed,
                new MemoryCandidateService.CandidateEdit(
                        MemoryType.EXPERIENCE, failed.title(), failed.content(), failed.confidence(),
                        failed.applicability(), null, List.of()),
                "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未确认");
    }

    @Test
    void manifestKeepsConfirmedReferenceFrozenUntilExplicitRefresh() {
        var fixture = fixture();
        var frozenMemoryId = memory(
                fixture, fixture.productArtifactId(), MemoryType.FACT,
                MemoryCandidateService.ARTIFACT_APPROVED, "发布事实", "负责人才能确认发布",
                MemoryApplicability.PROJECT, List.of());
        var messageId = "msg-" + UUID.randomUUID();
        jdbc.sql("""
                        insert into conversation_messages(
                            message_id, conversation_id, system_id, prd_id, sender_type, content, created_by)
                        values (:messageId, :conversationId, :systemId, :prdId,
                                'user', '只允许负责人发布', 'requester')
                        """)
                .param("messageId", messageId)
                .param("conversationId", fixture.conversationId())
                .param("systemId", fixture.systemId())
                .param("prdId", fixture.prdId())
                .update();
        var bundle = recall.recall(new ContextRecallQuery(
                fixture.systemId(), fixture.prdId(), "product", "负责人发布", messageId,
                Map.of("goal", "负责人确认发布"), List.of(), List.of(), "requester"));
        jdbc.sql("update conversation_messages set context_bundle_id = :bundleId where message_id = :messageId")
                .param("bundleId", bundle.bundleId())
                .param("messageId", messageId)
                .update();

        var manifestId = manifests.freeze(
                fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                List.of("MEM:" + frozenMemoryId), "{\"goal\":\"负责人确认发布\"}", "requester");
        var frozen = manifests.executionSnapshot(
                fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                manifestId, "负责人确认发布", Map.of());

        assertThat(frozen.requirementItems()).extracting(ContextItem::refId)
                .containsExactly("MEM:" + frozenMemoryId);
        jdbc.sql("update memory_items set content = '只有系统 Owner 才能确认发布' where memory_id = :memoryId")
                .param("memoryId", frozenMemoryId)
                .update();
        assertThat(manifests.executionSnapshot(
                fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                manifestId, "负责人确认发布", Map.of()).staleReferences())
                .containsExactly("MEM:" + frozenMemoryId);

        var refreshedId = manifests.refresh(
                fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                "owner", "refresh-request-1");
        var refreshed = manifests.executionSnapshot(
                fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                refreshedId, "负责人确认发布", Map.of());
        assertThat(refreshed.staleReferences()).isEmpty();
        assertThat(refreshed.requirementItems()).singleElement()
                .extracting(ContextItem::content)
                .isEqualTo("只有系统 Owner 才能确认发布");
    }

    private Fixture fixture() {
        var suffix = UUID.randomUUID().toString();
        var systemId = "sys-context-" + suffix;
        var prdId = "prd-context-" + suffix;
        var conversationId = "conv-context-" + suffix;
        var workItemId = "wi-context-" + suffix;
        var productArtifactId = "art-product-" + suffix;
        var planningArtifactId = "art-planning-" + suffix;
        var codingArtifactId = "art-coding-" + suffix;
        jdbc.sql("""
                        insert into systems(system_id, name, repo_path, owner_user_id)
                        values (:systemId, 'Context Test', '/tmp/context-test', 'owner')
                        """)
                .param("systemId", systemId)
                .update();
        jdbc.sql("""
                        insert into prd_sessions(
                            prd_id, system_id, conversation_id, draft_json, missing_fields, status, created_by)
                        values (:prdId, :systemId, :conversationId, '{}'::jsonb, '[]'::jsonb,
                                'waiting_user_confirm', 'requester')
                        """)
                .param("prdId", prdId)
                .param("systemId", systemId)
                .param("conversationId", conversationId)
                .update();
        jdbc.sql("""
                        insert into artifact_roots(root_artifact_id, system_id, prd_id)
                        values (:rootArtifactId, :systemId, :prdId)
                        """)
                .param("rootArtifactId", productArtifactId)
                .param("systemId", systemId)
                .param("prdId", prdId)
                .update();
        var fixture = new Fixture(
                systemId, prdId, conversationId, workItemId, suffix,
                productArtifactId, planningArtifactId, codingArtifactId,
                "art-coding-failed-" + suffix);
        artifact(fixture, productArtifactId, "PRODUCT", 1, "APPROVED", null, null);
        artifact(fixture, planningArtifactId, "PLANNING", 1, "APPROVED", productArtifactId, null);
        artifact(fixture, codingArtifactId, "CODING", 1, "APPROVED", planningArtifactId, null);
        return fixture;
    }

    private void artifact(
            Fixture fixture,
            String artifactId,
            String artifactType,
            int version,
            String status,
            String parentArtifactId,
            String supersedesArtifactId) {
        jdbc.sql("""
                        insert into artifacts(
                            artifact_id, artifact_type, root_artifact_id, system_id, prd_id,
                            work_item_id, case_id, version, status, parent_artifact_id,
                            supersedes_artifact_id, content_json, content_hash, idempotency_key,
                            created_by, reviewed_by, reviewed_at)
                        values (
                            :artifactId, :artifactType, :rootArtifactId, :systemId, :prdId,
                            :workItemId, :caseId, :version, :status, :parentArtifactId,
                            :supersedesArtifactId, '{}'::jsonb, :contentHash, :idempotencyKey,
                            'test', :reviewedBy, case when :reviewedBy is null then null else now() end)
                        """)
                .param("artifactId", artifactId)
                .param("artifactType", artifactType)
                .param("rootArtifactId", fixture.productArtifactId())
                .param("systemId", fixture.systemId())
                .param("prdId", fixture.prdId())
                .param("workItemId", fixture.workItemId())
                .param("caseId", "case-" + fixture.suffix())
                .param("version", version)
                .param("status", status)
                .param("parentArtifactId", parentArtifactId)
                .param("supersedesArtifactId", supersedesArtifactId)
                .param("contentHash", "hash-" + artifactId)
                .param("idempotencyKey", "key-" + artifactId)
                .param("reviewedBy", "APPROVED".equals(status) ? "owner" : null)
                .update();
    }

    private String memory(
            Fixture fixture,
            String artifactId,
            MemoryType type,
            String sourceKind,
            String title,
            String content,
            MemoryApplicability applicability,
            List<String> targetRefs) {
        var candidate = candidates.create(new MemoryCandidateService.CandidateInput(
                fixture.systemId(), fixture.systemId(), type, artifactId, sourceKind,
                title, content, 0.9, applicability, null, targetRefs,
                List.of("evt-" + UUID.randomUUID()), "evt-source-" + UUID.randomUUID(),
                "memory-extractor"));
        return candidates.approve(candidate, new MemoryCandidateService.CandidateEdit(
                type, title, content, 0.9, applicability, null, targetRefs), "owner").memoryId();
    }

    private ContextRecallQuery query(
            Fixture fixture,
            String phase,
            List<String> artifactSourceIds,
            String text) {
        return new ContextRecallQuery(
                fixture.systemId(), fixture.prdId(), phase, text, null,
                Map.of(), List.of("target-" + fixture.suffix()), fixture.systemId(),
                artifactSourceIds, List.<ConversationMessage>of(), "requester");
    }

    private void knowledge(String systemId, String entryId, String title, String route) {
        jdbc.sql("""
                        insert into system_knowledge(
                            entry_id, system_id, kind, title, anchor_texts, route_path,
                            status, source, source_ref, created_by)
                        values (:entryId, :systemId, 'page', :title, :title, :route,
                                'approved', 'manual', :entryId, 'test')
                        """)
                .param("entryId", entryId)
                .param("systemId", systemId)
                .param("title", title)
                .param("route", route)
                .update();
    }

    private record Fixture(
            String systemId,
            String prdId,
            String conversationId,
            String workItemId,
            String suffix,
            String productArtifactId,
            String planningArtifactId,
            String codingArtifactId,
            String failedCodingArtifactId) {
    }
}
