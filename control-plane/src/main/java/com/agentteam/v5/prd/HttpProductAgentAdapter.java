package com.agentteam.v5.prd;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

@Component
@Profile("llm")
public class HttpProductAgentAdapter implements ProductAgentPort {
    private final RestClient client;
    private final String endpoint;

    public HttpProductAgentAdapter(RestClient.Builder builder, @Value("${agent-team.product-agent.url}") String endpoint) {
        this.client = builder.build();
        this.endpoint = endpoint;
    }

    @Override
    public DraftResult updateDraft(String systemId, String content, java.util.Map<String, Object> currentDraft,
                                   java.util.List<String> missingFields,
                                   java.util.List<ConversationMessage> conversationHistory,
                                   java.util.List<String> approvedMemories) {
        // 真实 ProductAgent 通过 HTTP adapter 接入，控制面不关心模型厂商。
        try {
            return client.post()
                    .uri(endpoint)
                    .body(new DraftRequest(systemId, content, currentDraft, missingFields, conversationHistory, approvedMemories))
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
            @JsonProperty("approved_memories") java.util.List<String> approvedMemories) {
    }
}
