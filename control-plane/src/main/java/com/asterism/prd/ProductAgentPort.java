package com.asterism.prd;

import com.asterism.context.ContextItem;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface ProductAgentPort {
    DraftResult updateDraft(
            String systemId,
            String content,
            PrdContent currentDraft,
            List<String> missingFields,
            List<ConversationMessage> conversationHistory,
            List<ContextItem> contextItems);

    MemoryCandidateResult extractMemoryCandidates(
            String systemId,
            PrdContent draft,
            List<String> targetRefs,
            List<ContextItem> contextItems);

    record PrdContent(
            String title,
            String goal,
            String scope,
            List<String> acceptanceCriteria) {

        public PrdContent {
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }

    record PrdPatch(
            String title,
            String goal,
            String scope,
            List<String> acceptanceCriteria) {

        public PrdPatch {
            acceptanceCriteria = acceptanceCriteria == null ? null : List.copyOf(acceptanceCriteria);
        }
    }

    record DraftResult(
            PrdPatch patch,
            @JsonProperty("assistant_message") String assistantMessage,
            Map<String, List<String>> citations) {

        public DraftResult {
            var normalizedCitations = new LinkedHashMap<String, List<String>>();
            if (citations != null) citations.forEach((key, refs) ->
                    normalizedCitations.put(key, refs == null ? List.of() : List.copyOf(refs)));
            citations = Collections.unmodifiableMap(normalizedCitations);
        }
    }

    record MemoryCandidateProposal(
            String category,
            String audience,
            String title,
            String content,
            @JsonProperty("target_refs") List<String> targetRefs,
            @JsonProperty("evidence_refs") List<String> evidenceRefs) {

        public MemoryCandidateProposal {
            targetRefs = targetRefs == null ? List.of() : List.copyOf(targetRefs);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    record MemoryCandidateResult(List<MemoryCandidateProposal> candidates) {
        public MemoryCandidateResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
