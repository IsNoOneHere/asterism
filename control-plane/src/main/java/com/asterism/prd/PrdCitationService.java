package com.asterism.prd;

import com.asterism.context.ContextBundle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PrdCitationService {
    public CitationResult validateAndMerge(
            ContextBundle bundle,
            PrdDraft current,
            PrdDraft updated,
            ProductAgentPort.DraftResult result) {
        var allowed = bundle.items().stream().map(item -> item.refId()).collect(java.util.stream.Collectors.toSet());
        var patch = result.patch();
        var acceptedKeys = patchKeys(patch, updated);
        var citations = new LinkedHashMap<>(citationMap(current));
        removeChangedFields(citations, patch);
        for (var entry : result.citations().entrySet()) {
            if (!acceptedKeys.contains(entry.getKey())) {
                throw new IllegalArgumentException("Product Agent 返回了未修改字段的引用: " + entry.getKey());
            }
            var refs = entry.getValue() == null ? List.<String>of() : entry.getValue().stream().distinct().toList();
            requireAllowed(allowed, refs);
            citations.put(entry.getKey(), refs);
        }
        var used = new LinkedHashSet<String>();
        citations.values().forEach(used::addAll);
        return new CitationResult(List.copyOf(used), Collections.unmodifiableMap(citations));
    }

    public List<String> references(PrdDraft draft) {
        var refs = new LinkedHashSet<String>();
        citationMap(draft).values().forEach(refs::addAll);
        return new ArrayList<>(refs);
    }

    private Set<String> patchKeys(ProductAgentPort.PrdPatch patch, PrdDraft updated) {
        var keys = new LinkedHashSet<String>();
        if (patch.title() != null) keys.add("title");
        if (patch.goal() != null) keys.add("goal");
        if (patch.scope() != null) keys.add("scope");
        if (patch.acceptanceCriteria() != null) {
            for (var index = 0; index < updated.acceptanceCriteria().size(); index++) {
                keys.add("AC-" + (index + 1));
            }
        }
        return Set.copyOf(keys);
    }

    private void removeChangedFields(Map<String, List<String>> citations, ProductAgentPort.PrdPatch patch) {
        if (patch.title() != null) citations.remove("title");
        if (patch.goal() != null) citations.remove("goal");
        if (patch.scope() != null) citations.remove("scope");
        if (patch.acceptanceCriteria() != null) citations.keySet().removeIf(key -> key.startsWith("AC-"));
    }

    private Map<String, List<String>> citationMap(PrdDraft draft) {
        var raw = draft.extras().get("citations");
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        var citations = new LinkedHashMap<String, List<String>>();
        values.forEach((key, value) -> {
            if (value instanceof List<?> refs) {
                citations.put(String.valueOf(key), refs.stream().map(String::valueOf).toList());
            }
        });
        return citations;
    }

    private void requireAllowed(java.util.Set<String> allowed, List<String> refs) {
        var fabricated = refs.stream().filter(ref -> !allowed.contains(ref)).toList();
        if (!fabricated.isEmpty()) {
            throw new IllegalArgumentException("Product Agent 返回了未召回的上下文引用: " + String.join(",", fabricated));
        }
    }

    public record CitationResult(List<String> usedRefs, Map<String, List<String>> citations) {
    }
}
