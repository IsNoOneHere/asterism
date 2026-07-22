package com.asterism.context;

import java.util.Optional;

public interface ContextReferenceValidator {
    String type();

    Optional<ContextItem> current(ContextItem item);

    default boolean isCurrent(ContextItem item) {
        return current(item).map(value -> value.contentHash().equals(item.contentHash())).orElse(false);
    }
}
