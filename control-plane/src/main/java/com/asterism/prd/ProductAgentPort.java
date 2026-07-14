package com.asterism.prd;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public interface ProductAgentPort {
    DraftResult updateDraft(
            String systemId,
            String content,
            Map<String, Object> currentDraft,
            List<String> missingFields,
            List<ConversationMessage> conversationHistory,
            List<String> approvedMemories);

    record DraftResult(
            String title,
            Map<String, Object> draft,
            @JsonProperty("missing_fields") List<String> missingFields,
            @JsonProperty("assistant_message") String assistantMessage) {
    }
}
