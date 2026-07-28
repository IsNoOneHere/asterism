package com.asterism.prd;

import com.asterism.knowledge.KnowledgeMatchService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Product Agent 只能修改 PRD 语义字段，系统目标和扩展事实始终保留。 */
    public PrdDraft apply(ProductAgentPort.PrdPatch patch) {
        Objects.requireNonNull(patch, "Product Agent 未返回 PRD Patch");
        return new PrdDraft(
                patch.title() == null ? title : patch.title(),
                patch.goal() == null ? goal : patch.goal(),
                patch.scope() == null ? scope : patch.scope(),
                patch.acceptanceCriteria() == null ? acceptanceCriteria : patch.acceptanceCriteria(),
                suspectedTargets, targets, extras);
    }

    public ProductAgentPort.PrdContent productContent() {
        return new ProductAgentPort.PrdContent(title, goal, scope, acceptanceCriteria);
    }

    public PrdDraft withCitations(Map<String, List<String>> citations, List<String> usedContextRefs) {
        var updatedExtras = new LinkedHashMap<>(extras);
        updatedExtras.put("citations", citations == null ? Map.of() : citations);
        updatedExtras.put("usedContextRefs", usedContextRefs == null ? List.of() : usedContextRefs);
        return new PrdDraft(title, goal, scope, acceptanceCriteria, suspectedTargets, targets, updatedExtras);
    }
}
