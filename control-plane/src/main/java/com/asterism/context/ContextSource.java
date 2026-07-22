package com.asterism.context;

import java.util.List;

public interface ContextSource {
    String type();

    List<ContextItem> recall(ContextRecallQuery query);
}
