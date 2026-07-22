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
    void validatesAndKeepsOnlyRecalledReferences() {
        var result = citations.validate(bundle(), new ProductAgentPort.DraftResult(
                "登录页", Map.of("goal", "明确错误"), List.of(), "完成",
                List.of("MEM:mem-1"), Map.of("goal", List.of("MSG:msg-1", "MEM:mem-1")), List.of()));

        assertThat(result.usedRefs()).containsExactly("MSG:msg-1", "MEM:mem-1");
        assertThat(result.citations()).containsEntry("goal", List.of("MSG:msg-1", "MEM:mem-1"));
    }

    @Test
    void rejectsFabricatedReference() {
        assertThatThrownBy(() -> citations.validate(bundle(), new ProductAgentPort.DraftResult(
                "登录页", Map.of(), List.of(), "完成", List.of(),
                Map.of("goal", List.of("MEM:not-recalled")), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEM:not-recalled");
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
