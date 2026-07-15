package com.asterism.prd;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v5")
public class PrdController {
    private final PrdConversationService conversations;
    private final PrdConfirmationService confirmations;

    public PrdController(PrdConversationService conversations, PrdConfirmationService confirmations) {
        this.conversations = conversations;
        this.confirmations = confirmations;
    }

    @PostMapping("/systems/{systemId}/prd/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PrdConversationService.PrdMessageResponse message(
            @PathVariable String systemId,
            @Valid @RequestBody PrdConversationService.PrdMessageRequest request,
            Authentication actor) {
        return conversations.message(systemId, request, actor);
    }

    @PostMapping("/prd-sessions/{prdId}/targets/confirm")
    TargetConfirmationResponse confirmTargets(
            @PathVariable String prdId,
            @RequestBody TargetConfirmationRequest request,
            Authentication actor) {
        return new TargetConfirmationResponse(conversations.confirmTargets(
                prdId, request.entryIds(), request.accepted() == null || request.accepted(), actor));
    }

    @PostMapping("/prd-sessions/{prdId}/confirm")
    PrdConfirmationService.PrdConfirmResponse confirm(@PathVariable String prdId, Authentication actor) {
        return confirmations.confirm(prdId, actor);
    }

    @PatchMapping("/prd-sessions/{prdId}/draft")
    PrdConversationService.DraftUpdateResponse updateDraft(
            @PathVariable String prdId,
            @RequestBody DraftUpdateRequest request,
            Authentication actor) {
        return conversations.updateDraft(prdId, request.title(), request.goal(), request.acceptanceCriteria(), actor);
    }

    public record TargetConfirmationRequest(List<String> entryIds, Boolean accepted) {
        public TargetConfirmationRequest(List<String> entryIds) {
            this(entryIds, true);
        }
    }

    public record TargetConfirmationResponse(Map<String, Object> draft) {
    }

    public record DraftUpdateRequest(String title, String goal, List<String> acceptanceCriteria) {
    }
}
