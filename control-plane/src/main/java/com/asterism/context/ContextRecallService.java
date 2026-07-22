package com.asterism.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContextRecallService {
    private static final Logger log = LoggerFactory.getLogger(ContextRecallService.class);
    private static final int CHARACTER_BUDGET = 8_000;
    private static final Map<String, Integer> TYPE_LIMITS = Map.of(
            "memory", 5,
            "system_knowledge", 3,
            "user_message", 6);

    private final List<ContextSource> sources;
    private final ContextBundleStore bundles;

    public ContextRecallService(List<ContextSource> sources, ContextBundleStore bundles) {
        this.sources = List.copyOf(sources);
        this.bundles = bundles;
    }

    public ContextBundle recall(ContextRecallQuery query) {
        var candidates = sources.stream()
                .flatMap(source -> source.recall(query).stream())
                .filter(item -> item.supports(query.phase()))
                .sorted(Comparator.comparingDouble(ContextItem::relevance).reversed()
                        .thenComparing(ContextItem::refId))
                .toList();
        var counts = new HashMap<String, Integer>();
        var selected = new ArrayList<ContextItem>();
        var characters = 0;
        for (var item : candidates) {
            var limit = TYPE_LIMITS.getOrDefault(item.type(), 0);
            if (counts.getOrDefault(item.type(), 0) >= limit) continue;
            if (characters + item.content().length() > CHARACTER_BUDGET) continue;
            selected.add(item);
            counts.merge(item.type(), 1, Integer::sum);
            characters += item.content().length();
        }
        var queryHash = ContextHash.sha256(String.join("|",
                query.systemId(), query.phase(), query.searchText(),
                query.targetRefs().stream().sorted().toList().toString()));
        var bundle = new ContextBundle("bundle-" + UUID.randomUUID(), query.systemId(), query.prdId(),
                query.phase(), queryHash, selected, Instant.now());
        bundles.save(bundle, query.actorId());
        log.info("上下文召回完成 system={} prdId={} phase={} items={} chars={}",
                query.systemId(), query.prdId(), query.phase(), selected.size(), characters);
        return bundle;
    }
}
