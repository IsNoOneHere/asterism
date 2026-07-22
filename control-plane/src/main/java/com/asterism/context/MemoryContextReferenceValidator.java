package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class MemoryContextReferenceValidator implements ContextReferenceValidator {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public MemoryContextReferenceValidator(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public Optional<ContextItem> current(ContextItem item) {
        var memoryId = item.refId().substring("MEM:".length());
        return jdbc.sql("""
                        select audience, content, source_event_id, metadata_json::text,
                               coalesce(nullif(metadata_json ->> 'title', ''), left(content, 80)) as title
                        from memory_items where memory_id = :id and status = 'approved'
                        """)
                .param("id", memoryId)
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(item.refId(), type(), rs.getString("audience"), rs.getString("title"),
                            content, targetRefs(rs.getString("metadata_json")), rs.getString("source_event_id"),
                            ContextHash.sha256(content), item.relevance());
                })
                .optional();
    }

    private List<String> targetRefs(String metadataJson) {
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
            var value = metadata.get("targetRefs");
            return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }
}
