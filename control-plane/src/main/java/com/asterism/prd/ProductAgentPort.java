package com.asterism.prd;

import com.asterism.context.ContextItem;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public interface ProductAgentPort {
    DraftResult updateDraft(
            String systemId,
            String content,
            Map<String, Object> currentDraft,
            List<String> missingFields,
            List<ConversationMessage> conversationHistory,
            List<ContextItem> contextItems);

    record DraftResult(
            String title,
            Map<String, Object> draft,
            @JsonProperty("missing_fields") List<String> missingFields,
            @JsonProperty("assistant_message") String assistantMessage,
            @JsonProperty("used_context_refs") List<String> usedContextRefs,
            Map<String, List<String>> citations,
            @JsonProperty("memory_candidates") List<MemoryCandidateProposal> memoryCandidates) {

        public DraftResult(String title, Map<String, Object> draft, List<String> missingFields,
                           String assistantMessage) {
            this(title, draft, missingFields, assistantMessage, List.of(), Map.of(), List.of());
        }

        public DraftResult {
            usedContextRefs = usedContextRefs == null ? List.of() : List.copyOf(usedContextRefs);
            var normalizedCitations = new LinkedHashMap<String, List<String>>();
            if (citations != null) citations.forEach((key, refs) ->
                    normalizedCitations.put(key, refs == null ? List.of() : List.copyOf(refs)));
            citations = java.util.Collections.unmodifiableMap(normalizedCitations);
            memoryCandidates = memoryCandidates == null ? List.of() : List.copyOf(memoryCandidates);
        }
    }

    record MemoryCandidateProposal(
            String category,
            String audience,
            String title,
            String content,
            @JsonProperty("target_refs") List<String> targetRefs,
            @JsonProperty("evidence_refs") List<String> evidenceRefs) {
    }
}
