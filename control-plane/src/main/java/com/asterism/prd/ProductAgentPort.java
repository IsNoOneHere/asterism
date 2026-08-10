package com.asterism.prd;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface ProductAgentPort {
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

}
