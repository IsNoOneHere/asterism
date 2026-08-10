package com.asterism.prd;

import com.asterism.vision.UiObservation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProductAgentExecutionEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("event_type") EventType eventType,
        String stage,
        Integer attempt,
        @JsonProperty("failure_code") String failureCode,
        ProductAgentPort.DraftResult result,
        @JsonProperty("generated_artifact_candidate") Map<String, Object> generatedArtifactCandidate,
        List<UiObservation> observations,
        @JsonProperty("image_analysis_failed") boolean imageAnalysisFailed) {

    public ProductAgentExecutionEvent {
        generatedArtifactCandidate = generatedArtifactCandidate == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(generatedArtifactCandidate));
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public enum EventType {
        Started,
        Heartbeat,
        Completed,
        Failed,
        Cancelled;

        @JsonCreator
        public static EventType from(String value) {
            for (var type : values()) {
                if (type.name().equalsIgnoreCase(value)) return type;
            }
            throw new IllegalArgumentException("未知 Product Agent execution event: " + value);
        }
    }
}
