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

    public PrdDraft preserveTargets(PrdDraft current) {
        var mergedExtras = new LinkedHashMap<>(current.extras);
        mergedExtras.putAll(extras);
        return new PrdDraft(title, goal, scope, acceptanceCriteria,
                suspectedTargets.isEmpty() ? current.suspectedTargets : suspectedTargets,
                current.targets.isEmpty() ? targets : current.targets,
                mergedExtras);
    }
}
