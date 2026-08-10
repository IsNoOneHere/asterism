package com.asterism.prd;

import com.asterism.context.ContextBundleStore;
import com.asterism.context.ContextItem;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v5/conversations")
public class ConversationController {
    private final ConversationMessageRepository messages;
    private final SystemAccessService access;
    private final ObjectMapper objectMapper;
    private final ContextBundleStore bundles;
    private final ProductAgentExecutionService executions;

    public ConversationController(ConversationMessageRepository messages, SystemAccessService access,
                                  ObjectMapper objectMapper, ContextBundleStore bundles,
                                  ProductAgentExecutionService executions) {
        this.messages = messages;
        this.access = access;
        this.objectMapper = objectMapper;
        this.bundles = bundles;
        this.executions = executions;
    }

    @GetMapping("/{conversationId}")
    ConversationResponse messages(@PathVariable String conversationId, Authentication actor) {
        var result = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (!result.isEmpty()) {
            access.requireMember(result.getFirst().systemId(), actor);
        }
        var views = result.stream()
                .map(message -> new ConversationMessageView(
                message.messageId(), message.conversationId(), message.systemId(), message.prdId(), message.senderType(),
                message.content(), readList(message.attachmentIds()), readObjects(message.observationsJson()),
                readList(message.usedContextRefs()), readCitationMap(message.citationsJson()), contextItems(message),
                message.createdAt()))
                .toList();
        var prdId = result.isEmpty() ? null : result.getFirst().prdId();
        var latest = prdId == null ? null : executions.latestView(prdId);
        var active = latest != null && latest.status().active() ? latest : null;
        return new ConversationResponse(views, active, latest);
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private List<java.util.Map<String, Object>> readObjects(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private java.util.Map<String, List<String>> readCitationMap(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            return java.util.Map.of();
        }
    }

    private List<ContextItem> contextItems(ConversationMessage message) {
        if (message.contextBundleId() == null || message.contextBundleId().isBlank()) return List.of();
        return bundles.find(message.contextBundleId()).map(com.asterism.context.ContextBundle::items).orElse(List.of());
    }

    public record ConversationMessageView(String messageId, String conversationId, String systemId, String prdId,
                                          String senderType, String content, List<String> attachmentIds,
                                          List<java.util.Map<String, Object>> observations, List<String> usedContextRefs,
                                          java.util.Map<String, List<String>> citations, List<ContextItem> contextItems,
                                          java.time.Instant createdAt) {
    }

    public record ConversationResponse(List<ConversationMessageView> messages,
                                       ProductAgentExecutionView activeExecution,
                                       ProductAgentExecutionView latestExecution) {
    }
}
