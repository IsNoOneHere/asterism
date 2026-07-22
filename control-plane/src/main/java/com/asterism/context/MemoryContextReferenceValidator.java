package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
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
                        select m.audience, m.content, m.source_ref,
                               coalesce(nullif(m.metadata_json ->> 'title', ''), left(m.content, 80)) as title,
                               coalesce(jsonb_agg(mt.knowledge_entry_id order by mt.knowledge_entry_id)
                                   filter (where mt.knowledge_entry_id is not null), '[]'::jsonb)::text as target_refs
                        from memory_items m
                        left join memory_targets mt on mt.memory_id = m.memory_id
                        where m.memory_id = :id and m.status = 'approved'
                        group by m.memory_id, m.audience, m.content, m.source_ref, m.metadata_json
                        """)
                .param("id", memoryId)
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(item.refId(), type(), rs.getString("audience"), rs.getString("title"),
                            content, readList(rs.getString("target_refs")), rs.getString("source_ref"),
                            ContextHash.sha256(content), item.relevance());
                })
                .optional();
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }
}
