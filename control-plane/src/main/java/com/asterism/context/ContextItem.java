package com.asterism.context;

import java.util.List;

/** 记忆、系统知识和对话统一进入 Agent 的最小上下文单元。 */
public record ContextItem(
        String refId,
        String type,
        String audience,
        String title,
        String content,
        List<String> targetRefs,
        String sourceRef,
        String contentHash,
        double relevance) {

    public ContextItem {
        targetRefs = targetRefs == null ? List.of() : List.copyOf(targetRefs);
    }

    public boolean supports(String phase) {
        return "both".equals(audience) || phase.equals(audience);
    }
}
