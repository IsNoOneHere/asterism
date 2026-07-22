package com.asterism.context;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MessageContextReferenceValidator implements ContextReferenceValidator {
    private final JdbcClient jdbc;

    public MessageContextReferenceValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "user_message";
    }

    @Override
    public Optional<ContextItem> current(ContextItem item) {
        var messageId = item.refId().substring("MSG:".length());
        return jdbc.sql("""
                        select prd_id, content from conversation_messages
                        where message_id = :id and sender_type = 'user'
                        """)
                .param("id", messageId)
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(item.refId(), type(), "product", item.title(), content,
                            List.of(), rs.getString("prd_id"), ContextHash.sha256(content), item.relevance());
                })
                .optional();
    }
}
