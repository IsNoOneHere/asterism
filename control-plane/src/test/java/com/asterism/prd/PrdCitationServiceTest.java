package com.asterism.prd;

import com.asterism.context.ContextBundle;
import com.asterism.context.ContextHash;
import com.asterism.context.ContextItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdCitationServiceTest {
    private final PrdCitationService citations = new PrdCitationService();

    @Test
    void validatesPatchCitationsAndKeepsUnchangedFieldProvenance() {
        var current = new PrdDraft("登录页", "旧目标", "code_change", List.of("旧标准"),
                List.of(), List.of(), Map.of("citations", Map.of("title", List.of("MSG:msg-1"))));
        var patch = new ProductAgentPort.PrdPatch(null, "明确错误", null, null);
        var updated = current.apply(patch);
        var result = citations.validateAndMerge(bundle(), current, updated, new ProductAgentPort.DraftResult(
                patch, "完成", Map.of("goal", List.of("MEM:mem-1"))));

        assertThat(result.usedRefs()).containsExactly("MSG:msg-1", "MEM:mem-1");
        assertThat(result.citations())
                .containsEntry("title", List.of("MSG:msg-1"))
                .containsEntry("goal", List.of("MEM:mem-1"));
    }

    @Test
    void rejectsFabricatedReference() {
        var current = new PrdDraft("登录页", "旧目标", "code_change", List.of(),
                List.of(), List.of(), Map.of());
        var patch = new ProductAgentPort.PrdPatch(null, "明确错误", null, null);
        assertThatThrownBy(() -> citations.validateAndMerge(bundle(), current, current.apply(patch),
                new ProductAgentPort.DraftResult(
                        patch, "完成", Map.of("goal", List.of("MEM:not-recalled")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEM:not-recalled");
    }

    @Test
    void rejectsCitationForFieldOutsidePatch() {
        var current = new PrdDraft("登录页", "旧目标", "code_change", List.of(),
                List.of(), List.of(), Map.of());
        var patch = new ProductAgentPort.PrdPatch(null, "明确错误", null, null);

        assertThatThrownBy(() -> citations.validateAndMerge(bundle(), current, current.apply(patch),
                new ProductAgentPort.DraftResult(
                        patch, "完成", Map.of("title", List.of("MSG:msg-1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未修改字段");
    }

    private ContextBundle bundle() {
        return new ContextBundle("bundle-1", "sys-1", "prd-1", "product", "hash", List.of(
                item("MSG:msg-1", "user_message"), item("MEM:mem-1", "memory")), Instant.now());
    }

    private ContextItem item(String refId, String type) {
        return new ContextItem(refId, type, "product", refId, "内容", List.of(), "source",
                ContextHash.sha256("内容"), 1);
    }
}
