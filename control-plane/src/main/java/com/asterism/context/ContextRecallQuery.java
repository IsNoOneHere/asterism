package com.asterism.context;

import com.asterism.memory.MemoryType;
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
        String projectScope,
        List<String> artifactSourceIds,
        List<ConversationMessage> conversationHistory,
        String actorId) {

    public ContextRecallQuery {
        // 模型草稿允许保留 null 扩展字段，不能让 Map.copyOf 在召回入口抛出 NPE。
        currentDraft = currentDraft == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(currentDraft));
        targetRefs = targetRefs == null ? List.of() : List.copyOf(targetRefs);
        projectScope = projectScope == null || projectScope.isBlank() ? systemId : projectScope;
        artifactSourceIds = artifactSourceIds == null ? List.of() : List.copyOf(artifactSourceIds);
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
    }

    public ContextRecallQuery(
            String systemId,
            String prdId,
            String phase,
            String userMessage,
            String currentMessageId,
            Map<String, Object> currentDraft,
            List<String> targetRefs,
            List<ConversationMessage> conversationHistory,
            String actorId) {
        this(systemId, prdId, phase, userMessage, currentMessageId, currentDraft, targetRefs,
                systemId, List.of(), conversationHistory, actorId);
    }

    public List<MemoryType> memoryTypes() {
        return switch (phase) {
            // Product 的 HISTORY 对应已确认的历史 EXPERIENCE，业务规则收敛在 FACT 中。
            case "product" -> List.of(MemoryType.FACT, MemoryType.EXPERIENCE);
            case "planning" -> List.of(MemoryType.FACT, MemoryType.DECISION, MemoryType.CONSTRAINT);
            case "coding", "execution" -> List.of(MemoryType.CONSTRAINT, MemoryType.EXPERIENCE);
            default -> List.of();
        };
    }

    public boolean supportsSource(String sourceType) {
        return switch (phase) {
            case "product" -> "memory".equals(sourceType) || "user_message".equals(sourceType);
            case "planning", "coding", "execution" ->
                    "memory".equals(sourceType) || "system_knowledge".equals(sourceType);
            default -> false;
        };
    }

    public String searchText() {
        return String.join(" ", userMessage == null ? "" : userMessage,
                String.valueOf(currentDraft.getOrDefault("title", "")),
                String.valueOf(currentDraft.getOrDefault("goal", "")),
                String.valueOf(currentDraft.getOrDefault("scope", "")),
                String.valueOf(currentDraft.getOrDefault("acceptanceCriteria", ""))).trim();
    }
}
