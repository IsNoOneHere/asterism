package com.asterism.prd;

import com.asterism.context.ContextItem;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private final String workerToken;

    public HttpProductAgentAdapter(RestClient.Builder builder,
                                   @Value("${asterism.product-agent.url}") String endpoint,
                                   @Value("${asterism.worker-callback.token:dev-worker-token}") String workerToken) {
        this.client = builder.build();
        this.endpoint = endpoint;
        this.workerToken = workerToken;
    }

    @Override
    public DraftResult updateDraft(String systemId, String content, java.util.Map<String, Object> currentDraft,
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
            throw new IllegalStateException("ProductAgent 调用失败: " + error.getStatusCode() + " " + error.getResponseBodyAsString(), error);
        }
    }

    public record DraftRequest(
            @JsonProperty("system_id") String systemId,
            String content,
            @JsonProperty("current_draft") java.util.Map<String, Object> currentDraft,
            @JsonProperty("missing_fields") java.util.List<String> missingFields,
            @JsonProperty("conversation_history") java.util.List<ConversationMessage> conversationHistory,
            @JsonProperty("context_items") java.util.List<ContextItem> contextItems) {
    }
}
