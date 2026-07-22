package com.asterism.context;

import com.asterism.prd.ConversationMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextRecallQuery(
        String systemId,
        String prdId,
        String phase,
        String userMessage,
        String currentMessageId,
        Map<String, Object> currentDraft,
        List<String> targetRefs,
        List<ConversationMessage> conversationHistory,
        String actorId) {

    public ContextRecallQuery {
        // 模型草稿允许保留 null 扩展字段，不能让 Map.copyOf 在召回入口抛出 NPE。
        currentDraft = currentDraft == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(currentDraft));
        targetRefs = targetRefs == null ? List.of() : List.copyOf(targetRefs);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public String searchText() {
        return String.join(" ", userMessage == null ? "" : userMessage,
                String.valueOf(currentDraft.getOrDefault("title", "")),
                String.valueOf(currentDraft.getOrDefault("goal", "")),
                String.valueOf(currentDraft.getOrDefault("scope", "")),
                String.valueOf(currentDraft.getOrDefault("acceptanceCriteria", ""))).trim();
    }
}
