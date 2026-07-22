package com.asterism.context;

import java.time.Instant;
import java.util.List;

public record ContextBundle(
        String bundleId,
        String systemId,
        String prdId,
        String phase,
        String queryHash,
        List<ContextItem> items,
        Instant createdAt) {

    public ContextBundle {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
