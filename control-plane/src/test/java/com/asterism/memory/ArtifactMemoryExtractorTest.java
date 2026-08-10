package com.asterism.memory;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactEvidence;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactType;
import com.asterism.event.DomainEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactMemoryExtractorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ArtifactMemoryExtractor extractor = new ArtifactMemoryExtractor(artifacts);

    @Test
    void approvedProductBecomesBusinessFactCandidate() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, null,
                Map.of(
                        "title", "订单搜索",
                        "goal", "让运营快速找到订单",
                        "scope", "订单列表",
                        "acceptanceCriteria", List.of("支持按订单号搜索"),
                        "targets", List.of(Map.of("entryId", "page-orders")),
                        "auditRefs", List.of("PRDConfirmed:prd-1")));
        when(artifacts.findAncestors(product.artifactId())).thenReturn(List.of(product));

        var values = extractor.extract(product, event("PRDConfirmed"), null);

        assertThat(values).singleElement().satisfies(candidate -> {
            assertThat(candidate.memoryType()).isEqualTo(MemoryType.FACT);
            assertThat(candidate.artifactSourceId()).isEqualTo(product.artifactId());
            assertThat(candidate.content()).contains("业务目标", "业务规则");
            assertThat(candidate.targetRefs()).containsExactly("page-orders");
        });
    }

    @Test
    void approvedPlanningSeparatesDecisionAndConstraintAndDropsAlternatives() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, null,
                Map.of("targets", List.of(Map.of("entryId", "page-orders"))));
        var planning = artifact(
                "art-plan-1", ArtifactType.PLANNING, ArtifactStatus.APPROVED, product.artifactId(),
                Map.of(
                        "planMarkdown", """
                                # 方案
                                - 采用现有订单查询服务
                                - 禁止直接访问报表数据库
                                - 备选方案：新增 Elasticsearch
                                """,
                        "evidenceRefs", List.of("evidence-plan-1")));
        when(artifacts.findAncestors(planning.artifactId())).thenReturn(List.of(planning, product));

        var values = extractor.extract(planning, event("CodingPlanApproved"), null);

        assertThat(values).extracting(MemoryCandidateService.CandidateInput::memoryType)
                .containsExactly(MemoryType.DECISION, MemoryType.CONSTRAINT);
        assertThat(values).allSatisfy(candidate -> assertThat(candidate.content())
                .doesNotContain("Elasticsearch", "备选方案"));
    }

    @Test
    void codingCandidateNeverCopiesDiffAndValidationFailureRequiresHumanCompletion() {
        var product = artifact(
                "art-product-1", ArtifactType.PRODUCT, ArtifactStatus.APPROVED, null, Map.of());
        var coding = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.PROPOSED, "art-plan-1",
                Map.of(
                        "summary", "复用统一错误提示组件",
                        "repoChanges", List.of(Map.of(
                                "repo", "web",
                                "summary", "收敛登录错误展示",
                                "changedPaths", List.of("src/Login.tsx"),
                                "diffPatch", "diff --git a/src/Login.tsx b/src/Login.tsx"))));
        when(artifacts.findAncestors(coding.artifactId())).thenReturn(List.of(coding, product));

        var codingValues = extractor.extract(coding, event("ModificationCompleted"), null);

        assertThat(codingValues).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceKind()).isEqualTo(MemoryCandidateService.CODING_COMPLETED);
            assertThat(candidate.content()).contains("复用统一错误提示组件", "src/Login.tsx");
            assertThat(candidate.content()).doesNotContain("diff --git");
        });

        var failed = artifact(
                "art-code-1", ArtifactType.CODING, ArtifactStatus.REJECTED, "art-plan-1", Map.of());
        var evidence = new ArtifactEvidence(
                "evidence-validation-1", failed.artifactId(), "ValidationFailed",
                objectMapper.valueToTree(Map.of(
                        "failedCommand", "/usr/local/bin/npm test --token hidden",
                        "stderrTail", "完整日志不能进入记忆")),
                "transition-1", "evt-1", "worker", "hash", Instant.now());
        when(artifacts.findAncestors(failed.artifactId())).thenReturn(List.of(failed, product));

        var failureValues = extractor.extract(failed, event("ValidationFailed"), evidence);

        assertThat(failureValues).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceKind()).isEqualTo(MemoryCandidateService.VALIDATION_FAILED);
            assertThat(candidate.content()).contains("npm", "[待补充]");
            assertThat(candidate.content()).doesNotContain("hidden", "完整日志");
        });
    }

    private Artifact artifact(
            String id,
            ArtifactType type,
            ArtifactStatus status,
            String parent,
            Map<String, Object> content) {
        var now = Instant.now();
        return new Artifact(
                id, type, "art-product-1", "sys-1", "prd-1", "wi-1", "case-1",
                1, status, parent, null, null, objectMapper.valueToTree(content),
                "hash-" + id, "key-" + id, "worker", now,
                status == ArtifactStatus.APPROVED ? "owner" : null,
                status == ArtifactStatus.APPROVED ? now : null, "");
    }

    private DomainEventRecord event(String type) {
        return new DomainEventRecord(
                1L, "evt-" + type, type, "v5.0", "sys-1", "case-1",
                "prd-1", "wi-1", "worker", "worker", "{}", "case-1",
                null, type + "-1", Instant.now());
    }
}
