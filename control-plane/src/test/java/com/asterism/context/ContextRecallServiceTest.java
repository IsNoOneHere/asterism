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
    void recallAppliesAudienceTypeLimitsBudgetAndStableOrdering() {
        var candidates = new ArrayList<ContextItem>();
        candidates.add(item("MEM:z", "memory", "product", 9, "m".repeat(5_000)));
        candidates.add(item("MEM:a", "memory", "product", 8, "m".repeat(4_000)));
        for (var index = 0; index < 6; index++) {
            candidates.add(item("MEM:" + index, "memory", "product", 7 - index, "memory-" + index));
        }
        for (var index = 0; index < 4; index++) {
            candidates.add(item("KN:" + index, "system_knowledge", "both", 6 - index, "knowledge-" + index));
        }
        candidates.add(item("MSG:b", "user_message", "product", 1, "message-b"));
        candidates.add(item("MSG:a", "user_message", "product", 1, "message-a"));
        candidates.add(item("MEM:execution", "memory", "execution", 100, "execution-only"));
        var source = new ContextSource() {
            @Override public String type() { return "test"; }
            @Override public List<ContextItem> recall(ContextRecallQuery query) { return candidates; }
        };
        var store = mock(ContextBundleStore.class);
        var service = new ContextRecallService(List.of(source), store);

        var bundle = service.recall(new ContextRecallQuery(
                "sys-1", "prd-1", "product", "登录页", "msg-current", Map.of(), List.of(), List.of(), "user"));

        assertThat(bundle.items()).noneMatch(item -> "MEM:execution".equals(item.refId()));
        assertThat(bundle.items().stream().filter(item -> "memory".equals(item.type()))).hasSize(5);
        assertThat(bundle.items().stream().filter(item -> "system_knowledge".equals(item.type()))).hasSize(3);
        assertThat(bundle.items().stream().mapToInt(item -> item.content().length()).sum()).isLessThanOrEqualTo(8_000);
        assertThat(bundle.items()).extracting(ContextItem::refId).containsSubsequence("MSG:a", "MSG:b");
        verify(store).save(bundle, "user");
    }

    private ContextItem item(String refId, String type, String audience, double relevance, String content) {
        return new ContextItem(refId, type, audience, refId, content, List.of(), "source",
                ContextHash.sha256(content), relevance);
    }
}
