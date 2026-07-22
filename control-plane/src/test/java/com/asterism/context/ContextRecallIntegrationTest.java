package com.asterism.context;

import com.asterism.IntegrationDatabase;
import com.asterism.prd.ConversationMessage;
import com.asterism.memory.MemoryCandidateService;
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

@SpringBootTest
class ContextRecallIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired private JdbcClient jdbc;
    @Autowired private MemoryContextSource memories;
    @Autowired private SystemKnowledgeContextSource knowledge;
    @Autowired private ContextRecallService recall;
    @Autowired private RequirementContextManifestService manifests;
    @Autowired private MemoryCandidateService candidates;

    @Test
    void prdRecallUsesOnlyApprovedMatchingAudienceAndPlainTextKnowledge() {
        var fixture = fixture();
        var productId = "product-approved-" + fixture.suffix();
        var knowledgeId = "login-page-" + fixture.suffix();
        memory(fixture.systemId(), productId, "approved", "product", "登录页错误提示必须使用中文");
        memory(fixture.systemId(), "candidate-" + fixture.suffix(), "candidate", "both", "登录页候选规则");
        memory(fixture.systemId(), "execution-" + fixture.suffix(), "approved", "execution", "登录页实现经验");
        knowledge(fixture.systemId(), knowledgeId, "登录错误提示页面", "/login/" + fixture.suffix());
        var query = query(fixture, "product", "登录页错误提示");

        var recalledMemories = memories.recall(query);
        var recalledKnowledge = knowledge.recall(query);

        assertThat(recalledMemories).extracting(ContextItem::refId)
                .containsExactly("MEM:" + productId);
        assertThat(recalledKnowledge).extracting(ContextItem::refId)
                .contains("KN:" + knowledgeId);
    }

    @Test
    void manifestFreezesOnlyUsedReferencesAndRequiresExplicitRefreshAfterChange() {
        var fixture = fixture();
        var frozenMemoryId = "frozen-" + fixture.suffix();
        memory(fixture.systemId(), frozenMemoryId, "approved", "both", "负责人才能确认发布");
        var messageId = "msg-" + UUID.randomUUID();
        jdbc.sql("""
                        insert into conversation_messages(
                            message_id, conversation_id, system_id, prd_id, sender_type, content, created_by)
                        values (:messageId, :conversationId, :systemId, :prdId, 'user', '只允许负责人发布', 'requester')
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

        var manifestId = manifests.freeze(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                List.of("MEM:" + frozenMemoryId), "{\"goal\":\"负责人确认发布\"}", "requester");
        var newMemoryId = "new-after-confirm-" + fixture.suffix();
        memory(fixture.systemId(), newMemoryId, "approved", "product", "确认后新增的产品记忆");
        var frozen = manifests.executionSnapshot(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                manifestId, "负责人确认发布", Map.of());

        assertThat(frozen.requirementItems()).extracting(ContextItem::refId).containsExactly("MEM:" + frozenMemoryId);
        assertThat(frozen.requirementItems()).noneMatch(item -> ("MEM:" + newMemoryId).equals(item.refId()));
        jdbc.sql("update memory_items set content = '只有系统 Owner 才能确认发布' where memory_id = :memoryId")
                .param("memoryId", frozenMemoryId)
                .update();
        assertThat(manifests.executionSnapshot(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                manifestId, "负责人确认发布", Map.of()).staleReferences()).containsExactly("MEM:" + frozenMemoryId);

        var refreshedId = manifests.refresh(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                "owner", "refresh-request-1");
        var refreshed = manifests.executionSnapshot(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                refreshedId, "负责人确认发布", Map.of());
        assertThat(refreshedId).isNotEqualTo(manifestId);
        assertThat(refreshed.staleReferences()).isEmpty();
        assertThat(refreshed.requirementItems()).singleElement()
                .extracting(ContextItem::content).isEqualTo("只有系统 Owner 才能确认发布");
        assertThat(manifests.refresh(fixture.systemId(), fixture.prdId(), fixture.workItemId(),
                "owner", "refresh-request-1")).isEqualTo(refreshedId);
    }

    @Test
    void targetMatchRanksFirstAndCandidateCreationIsDeduplicatedWithoutAutoApproval() {
        var fixture = fixture();
        var targetId = "target-" + fixture.suffix();
        knowledge(fixture.systemId(), targetId, "登录页", "/login/" + fixture.suffix());
        var input = new MemoryCandidateService.CandidateInput(
                fixture.systemId(), "constraint", "product", "登录目标规则", "登录错误必须显示中文提示",
                "prd:" + fixture.prdId() + ":rule-1", List.of(targetId), List.of("MEM:evidence"),
                fixture.workItemId(), "", "requester");

        var candidate = candidates.create(input);

        assertThat(candidate.status()).isEqualTo("candidate");
        assertThat(jdbc.sql("select status from memory_items where memory_id = :id")
                .param("id", candidate.memoryId()).query(String.class).single()).isEqualTo("candidate");
        var approved = candidates.approve(candidate, new MemoryCandidateService.CandidateEdit(
                "constraint", "product", "登录目标规则", candidate.content(), List.of(targetId)), "owner");
        var duplicate = candidates.create(new MemoryCandidateService.CandidateInput(
                fixture.systemId(), "constraint", "product", "重复规则", "  登录错误必须显示中文提示  ",
                "prd:" + fixture.prdId() + ":rule-duplicate", List.of(), List.of("MEM:evidence"),
                fixture.workItemId(), "", "requester"));

        assertThat(duplicate.memoryId()).isEqualTo(approved.memoryId());
        var bundle = recall.recall(new ContextRecallQuery(
                fixture.systemId(), fixture.prdId(), "product", "完全无关的查询", null, Map.of(),
                List.of(targetId), List.of(), "requester"));
        assertThat(bundle.items()).isNotEmpty();
        assertThat(bundle.items().getFirst().refId()).isEqualTo("MEM:" + approved.memoryId());
        assertThat(bundle.items().getFirst().targetRefs()).containsExactly(targetId);
    }

    private Fixture fixture() {
        var suffix = UUID.randomUUID().toString();
        var systemId = "sys-context-" + suffix;
        var prdId = "prd-context-" + suffix;
        var conversationId = "conv-context-" + suffix;
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
        return new Fixture(systemId, prdId, conversationId, "wi-context-" + suffix, suffix);
    }

    private ContextRecallQuery query(Fixture fixture, String phase, String text) {
        return new ContextRecallQuery(fixture.systemId(), fixture.prdId(), phase, text, null,
                Map.of(), List.of(), List.<ConversationMessage>of(), "requester");
    }

    private void memory(String systemId, String memoryId, String status, String audience, String content) {
        jdbc.sql("""
                        insert into memory_items(
                            memory_id, system_id, content, status, audience, metadata_json, created_by)
                        values (:memoryId, :systemId, :content, :status, :audience,
                                '{"category":"constraint","title":"登录约束"}'::jsonb, 'test')
                        """)
                .param("memoryId", memoryId)
                .param("systemId", systemId)
                .param("content", content)
                .param("status", status)
                .param("audience", audience)
                .update();
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

    private record Fixture(String systemId, String prdId, String conversationId, String workItemId, String suffix) {
    }
}
