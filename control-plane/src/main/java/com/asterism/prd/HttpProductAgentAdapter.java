package com.asterism.prd;

import com.asterism.context.ContextItem;
import com.asterism.common.ModelInvocationException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Component
@Profile("llm")
public class HttpProductAgentAdapter implements ProductAgentPort {
    private final RestClient client;
    private final String endpoint;
    private final String memoryEndpoint;
    private final String workerToken;
    private final ObjectMapper objectMapper;

    public HttpProductAgentAdapter(RestClient.Builder builder,
                                   @Value("${asterism.product-agent.url}") String endpoint,
                                   @Value("${asterism.product-agent.memory-url}") String memoryEndpoint,
                                   @Value("${asterism.worker-callback.token:dev-worker-token}") String workerToken,
                                   ObjectMapper objectMapper) {
        this.client = builder.build();
        this.endpoint = endpoint;
        this.memoryEndpoint = memoryEndpoint;
        this.workerToken = workerToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public DraftResult updateDraft(String systemId, String content, PrdContent currentDraft,
                                   java.util.List<String> missingFields,
                                   java.util.List<ConversationMessage> conversationHistory,
                                   java.util.List<ContextItem> contextItems) {
        // 真实 ProductAgent 通过 HTTP adapter 接入，控制面不关心模型厂商。
        try {
            return client.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerToken)
                    .body(new DraftRequest(systemId, content, currentDraft, missingFields, conversationHistory, contextItems))
                    .retrieve()
                    .body(ProductAgentPort.DraftResult.class);
        } catch (RestClientResponseException error) {
            throw ModelInvocationException.from(error, objectMapper);
        }
    }

    @Override
    public MemoryCandidateResult extractMemoryCandidates(
            String systemId,
            PrdContent draft,
            java.util.List<String> targetRefs,
            java.util.List<ContextItem> contextItems) {
        try {
            return client.post()
                    .uri(memoryEndpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerToken)
                    .body(new MemoryCandidateRequest(systemId, draft, targetRefs, contextItems))
                    .retrieve()
                    .body(MemoryCandidateResult.class);
        } catch (RestClientResponseException error) {
            throw ModelInvocationException.from(error, objectMapper);
        }
    }

    public record DraftRequest(
            @JsonProperty("system_id") String systemId,
            String content,
            @JsonProperty("current_draft") PrdContent currentDraft,
            @JsonProperty("missing_fields") java.util.List<String> missingFields,
            @JsonProperty("conversation_history") java.util.List<ConversationMessage> conversationHistory,
            @JsonProperty("context_items") java.util.List<ContextItem> contextItems) {
    }

    public record MemoryCandidateRequest(
            @JsonProperty("system_id") String systemId,
            PrdContent draft,
            @JsonProperty("target_refs") java.util.List<String> targetRefs,
            @JsonProperty("context_items") java.util.List<ContextItem> contextItems) {
    }
}
