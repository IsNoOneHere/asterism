package com.asterism.knowledge;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v5/internal/systems/{systemId}/knowledge")
public class InternalKnowledgeController {
    private final SystemKnowledgeService knowledge;

    public InternalKnowledgeController(SystemKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @PostMapping("/candidates")
    CandidateResponse candidates(@PathVariable String systemId, @RequestBody CandidateBatch request) {
        return new CandidateResponse(knowledge.writeCandidates(systemId, request.entries(), "code_index", "asterism-worker"));
    }

    public record CandidateBatch(List<SystemKnowledgeService.CandidateRequest> entries) {
    }

    public record CandidateResponse(int created) {
    }
}
