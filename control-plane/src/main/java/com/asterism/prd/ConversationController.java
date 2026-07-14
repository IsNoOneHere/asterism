package com.asterism.prd;

import com.asterism.identity.SystemAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v5/conversations")
public class ConversationController {
    private final ConversationMessageRepository messages;
    private final SystemAccessService access;

    public ConversationController(ConversationMessageRepository messages, SystemAccessService access) {
        this.messages = messages;
        this.access = access;
    }

    @GetMapping("/{conversationId}")
    Iterable<ConversationMessage> messages(@PathVariable String conversationId, Authentication actor) {
        List<ConversationMessage> result = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (!result.isEmpty()) {
            access.requireMember(result.getFirst().systemId(), actor);
        }
        return result;
    }
}
