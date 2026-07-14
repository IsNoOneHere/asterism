package com.asterism.knowledge;

import com.asterism.identity.SystemAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v5/systems/{systemId}/knowledge")
public class SystemKnowledgeController {
    private final SystemKnowledgeService knowledge;
    private final SystemAccessService access;

    public SystemKnowledgeController(SystemKnowledgeService knowledge, SystemAccessService access) {
        this.knowledge = knowledge;
        this.access = access;
    }

    @GetMapping
    List<SystemKnowledgeService.KnowledgeView> list(@PathVariable String systemId,
                                                    @RequestParam(required = false) String status,
                                                    Authentication actor) {
        access.requireMember(systemId, actor);
        return knowledge.list(systemId, status);
    }

    @PostMapping
    SystemKnowledgeService.KnowledgeView create(@PathVariable String systemId,
                                                @RequestBody SystemKnowledgeService.CandidateRequest request,
                                                Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return knowledge.createManual(systemId, request, actor.getName());
    }

    @PatchMapping("/{entryId}/status")
    SystemKnowledgeService.KnowledgeView updateStatus(@PathVariable String systemId, @PathVariable String entryId,
                                                      @RequestBody StatusRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return knowledge.updateStatus(systemId, entryId, request.status(), actor.getName());
    }

    public record StatusRequest(String status) {
    }
}
