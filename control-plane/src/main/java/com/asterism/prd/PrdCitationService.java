package com.asterism.prd;

import com.asterism.context.ContextBundle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class PrdCitationService {
    public CitationResult validate(ContextBundle bundle, ProductAgentPort.DraftResult result) {
        var allowed = bundle.items().stream().map(item -> item.refId()).collect(java.util.stream.Collectors.toSet());
        var citations = new LinkedHashMap<String, List<String>>();
        var used = new LinkedHashSet<String>();
        for (var entry : result.citations().entrySet()) {
            var refs = entry.getValue() == null ? List.<String>of() : entry.getValue().stream().distinct().toList();
            requireAllowed(allowed, refs);
            citations.put(entry.getKey(), refs);
            used.addAll(refs);
        }
        requireAllowed(allowed, result.usedContextRefs());
        used.addAll(result.usedContextRefs());
        return new CitationResult(List.copyOf(used), Map.copyOf(citations));
    }

    public List<String> references(PrdDraft draft) {
        var citations = draft.extras().get("citations");
        if (!(citations instanceof Map<?, ?> values)) return List.of();
        var refs = new LinkedHashSet<String>();
        for (var value : values.values()) {
            if (value instanceof List<?> list) list.forEach(item -> refs.add(String.valueOf(item)));
        }
        return new ArrayList<>(refs);
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
