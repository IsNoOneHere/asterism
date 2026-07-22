package com.asterism.prd;

import com.asterism.context.ContextHash;
import com.asterism.memory.MemoryCandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PrdMemoryCandidateService {
    private final MemoryCandidateService candidates;
    private final PrdCitationService citations;
    private final ObjectMapper objectMapper;

    public PrdMemoryCandidateService(MemoryCandidateService candidates, PrdCitationService citations,
                                     ObjectMapper objectMapper) {
        this.candidates = candidates;
        this.citations = citations;
        this.objectMapper = objectMapper;
    }

    public void createCandidates(PrdSession session, PrdDraft draft, String workItemId, String actorId) {
        candidates.createAll(inputs(session, draft, workItemId, actorId));
    }

    List<MemoryCandidateService.CandidateInput> inputs(
            PrdSession session, PrdDraft draft, String workItemId, String actorId) {
        var raw = draft.extras().get("memoryCandidates");
        if (!(raw instanceof List<?> values)) return List.of();
        var allowedEvidence = new LinkedHashSet<>(citations.references(draft));
        var allowedTargets = draft.targets().stream()
                .map(com.asterism.knowledge.KnowledgeMatchService.SuspectedTarget::entryId)
                .collect(Collectors.toSet());
        var result = new ArrayList<MemoryCandidateService.CandidateInput>();
        for (var value : values) {
            ProductAgentPort.MemoryCandidateProposal proposal;
            try {
                proposal = objectMapper.convertValue(value, ProductAgentPort.MemoryCandidateProposal.class);
            } catch (IllegalArgumentException error) {
                continue;
            }
            var evidence = safeList(proposal.evidenceRefs()).stream().filter(allowedEvidence::contains).toList();
            if (evidence.isEmpty()) continue;
            var targets = safeList(proposal.targetRefs()).stream().filter(allowedTargets::contains).toList();
            var hash = ContextHash.sha256(String.valueOf(proposal.content()));
            result.add(new MemoryCandidateService.CandidateInput(
                    session.systemId(), proposal.category(), proposal.audience(), proposal.title(), proposal.content(),
                    "prd:" + session.prdId() + ":" + hash.substring(0, 16), targets, evidence,
                    workItemId, "", actorId));
        }
        return List.copyOf(result);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
