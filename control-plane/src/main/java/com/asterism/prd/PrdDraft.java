package com.asterism.prd;

import com.asterism.knowledge.KnowledgeMatchService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PrdDraft(
        String title,
        String goal,
        String scope,
        List<String> acceptanceCriteria,
        List<KnowledgeMatchService.SuspectedTarget> suspectedTargets,
        List<KnowledgeMatchService.SuspectedTarget> targets,
        Map<String, Object> extras) {

    public PrdDraft {
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        suspectedTargets = suspectedTargets == null ? List.of() : List.copyOf(suspectedTargets);
        targets = targets == null ? List.of() : List.copyOf(targets);
        extras = extras == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    public PrdDraft withTitle(String value) {
        return new PrdDraft(value, goal, scope, acceptanceCriteria, suspectedTargets, targets, extras);
    }

    public PrdDraft withSuspectedTargets(List<KnowledgeMatchService.SuspectedTarget> values) {
        return new PrdDraft(title, goal, scope, acceptanceCriteria, values, targets, extras);
    }

    public PrdDraft withTargets(List<KnowledgeMatchService.SuspectedTarget> values) {
        return new PrdDraft(title, goal, scope, acceptanceCriteria, suspectedTargets, values, extras);
    }

    public PrdDraft withManualChanges(String newTitle, String newGoal, List<String> newAcceptanceCriteria) {
        return new PrdDraft(newTitle == null ? title : newTitle, newGoal == null ? goal : newGoal, scope,
                newAcceptanceCriteria == null ? acceptanceCriteria : newAcceptanceCriteria,
                suspectedTargets, targets, extras);
    }

    public PrdDraft withCitations(Map<String, List<String>> citations, List<String> usedContextRefs) {
        var updatedExtras = new LinkedHashMap<>(extras);
        updatedExtras.put("citations", citations == null ? Map.of() : citations);
        updatedExtras.put("usedContextRefs", usedContextRefs == null ? List.of() : usedContextRefs);
        return new PrdDraft(title, goal, scope, acceptanceCriteria, suspectedTargets, targets, updatedExtras);
    }

    public PrdDraft withMemoryCandidates(List<ProductAgentPort.MemoryCandidateProposal> candidates) {
        var updatedExtras = new LinkedHashMap<>(extras);
        updatedExtras.put("memoryCandidates", candidates == null ? List.of() : List.copyOf(candidates));
        return new PrdDraft(title, goal, scope, acceptanceCriteria, suspectedTargets, targets, updatedExtras);
    }

    public PrdDraft preserveTargets(PrdDraft current) {
        var mergedExtras = new LinkedHashMap<>(current.extras);
        mergedExtras.putAll(extras);
        return new PrdDraft(title, goal, scope, acceptanceCriteria,
                suspectedTargets.isEmpty() ? current.suspectedTargets : suspectedTargets,
                current.targets.isEmpty() ? targets : current.targets,
                mergedExtras);
    }
}
