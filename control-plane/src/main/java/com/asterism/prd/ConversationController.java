package com.asterism.prd;

import com.asterism.context.ContextBundleStore;
import com.asterism.context.ContextItem;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.Instant;

@RestController
@RequestMapping("/api/v5/conversations")
public class ConversationController {
    private final ConversationMessageRepository messages;
    private final SystemAccessService access;
    private final ObjectMapper objectMapper;
    private final ContextBundleStore bundles;

    public ConversationController(ConversationMessageRepository messages, SystemAccessService access,
                                  ObjectMapper objectMapper, ContextBundleStore bundles) {
        this.messages = messages;
        this.access = access;
        this.objectMapper = objectMapper;
        this.bundles = bundles;
    }

    @GetMapping("/{conversationId}")
    @Transactional
    ConversationResponse messages(@PathVariable String conversationId, Authentication actor) {
        var result = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (!result.isEmpty()) {
            access.requireMember(result.getFirst().systemId(), actor);
        }
        var pending = result.stream()
                .filter(message -> PrdConversationService.PENDING_SENDER.equals(message.senderType()))
                .findFirst();
        if (pending.isPresent()
                && !pending.get().createdAt().isAfter(Instant.now().minusSeconds(PrdConversationService.PENDING_TIMEOUT_SECONDS))) {
            messages.completePending(pending.get().messageId(), "AI 暂时不可用，请重试");
            result = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
            pending = java.util.Optional.empty();
        }
        var views = result.stream()
                .filter(message -> !PrdConversationService.PENDING_SENDER.equals(message.senderType()))
                .map(message -> new ConversationMessageView(
                message.messageId(), message.conversationId(), message.systemId(), message.prdId(), message.senderType(),
                message.content(), readList(message.attachmentIds()), readObjects(message.observationsJson()),
                readList(message.usedContextRefs()), readCitationMap(message.citationsJson()), contextItems(message),
                message.createdAt()))
                .toList();
        return new ConversationResponse(views, pending.isPresent(), pending.map(ConversationMessage::createdAt).orElse(null));
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

    public record ConversationResponse(List<ConversationMessageView> messages, boolean pendingAssistant,
                                       Instant pendingSince) {
    }
}
