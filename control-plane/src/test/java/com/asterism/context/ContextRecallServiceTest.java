package com.asterism.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContextRecallServiceTest {
    @Test
    void recallAppliesPhaseSourcesTypeLimitsBudgetAndStableOrdering() {
        var memoryItems = new ArrayList<ContextItem>();
        memoryItems.add(item("MEM:z", "memory", "both", 9, "m".repeat(5_000)));
        memoryItems.add(item("MEM:a", "memory", "both", 8, "m".repeat(4_000)));
        for (var index = 0; index < 6; index++) {
            memoryItems.add(item("MEM:" + index, "memory", "both", 7 - index, "memory-" + index));
        }
        var knowledgeItems = new ArrayList<ContextItem>();
        for (var index = 0; index < 4; index++) {
            knowledgeItems.add(item(
                    "KN:" + index, "system_knowledge", "both", 6 - index, "knowledge-" + index));
        }
        var messageItems = List.of(
                item("MSG:b", "user_message", "product", 1, "message-b"),
                item("MSG:a", "user_message", "product", 1, "message-a"));
        memoryItems.add(item("MEM:coding", "memory", "coding", 100, "coding-only"));
        var store = mock(ContextBundleStore.class);
        var service = new ContextRecallService(List.of(
                source("memory", memoryItems),
                source("system_knowledge", knowledgeItems),
                source("user_message", messageItems)), store);

        var bundle = service.recall(new ContextRecallQuery(
                "sys-1", "prd-1", "product", "登录页", "msg-current", Map.of(), List.of(), List.of(), "user"));

        assertThat(bundle.items()).noneMatch(item -> "MEM:coding".equals(item.refId()));
        assertThat(bundle.items().stream().filter(item -> "memory".equals(item.type()))).hasSize(7);
        assertThat(bundle.items()).noneMatch(item -> "system_knowledge".equals(item.type()));
        assertThat(bundle.items().stream().mapToInt(item -> item.content().length()).sum()).isLessThanOrEqualTo(8_000);
        assertThat(bundle.items()).extracting(ContextItem::refId).containsSubsequence("MSG:a", "MSG:b");
        verify(store).save(bundle, "user");
    }

    private ContextSource source(String type, List<ContextItem> items) {
        return new ContextSource() {
            @Override public String type() { return type; }
            @Override public List<ContextItem> recall(ContextRecallQuery query) { return items; }
        };
    }

    private ContextItem item(String refId, String type, String audience, double relevance, String content) {
        return new ContextItem(refId, type, audience, refId, content, List.of(), "source",
                ContextHash.sha256(content), relevance);
    }
}
