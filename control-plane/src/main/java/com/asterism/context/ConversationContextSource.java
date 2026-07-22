package com.asterism.context;

import com.asterism.prd.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class ConversationContextSource implements ContextSource {
    @Override
    public String type() {
        return "user_message";
    }

    @Override
    public List<ContextItem> recall(ContextRecallQuery query) {
        var values = new LinkedHashMap<String, ContextItem>();
        var history = query.conversationHistory().stream()
                .filter(message -> "user".equals(message.senderType()))
                .toList();
        var start = Math.max(0, history.size() - 5);
        for (var index = start; index < history.size(); index++) {
            put(values, history.get(index), 1.0 + (index - start) * 0.01);
        }
        if (query.currentMessageId() != null && !query.currentMessageId().isBlank()) {
            var content = query.userMessage() == null ? "" : query.userMessage();
            values.put("MSG:" + query.currentMessageId(), new ContextItem(
                    "MSG:" + query.currentMessageId(), type(), "product", "当前用户输入", content,
                    List.of(), query.prdId(), ContextHash.sha256(content), 10.0));
        }
        return new ArrayList<>(values.values());
    }

    private void put(LinkedHashMap<String, ContextItem> values, ConversationMessage message, double relevance) {
        var content = message.content() == null ? "" : message.content();
        values.put("MSG:" + message.messageId(), new ContextItem(
                "MSG:" + message.messageId(), type(), "product", "历史用户输入", content,
                List.of(), message.prdId(), ContextHash.sha256(content), relevance));
    }
}
