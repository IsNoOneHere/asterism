package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryContextSource implements ContextSource {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public MemoryContextSource(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public List<ContextItem> recall(ContextRecallQuery query) {
        var targets = query.targetRefs().isEmpty() ? List.of("") : query.targetRefs();
        return jdbc.sql("""
                        select m.memory_id, m.audience, m.content, m.source_ref,
                               coalesce(nullif(m.metadata_json ->> 'title', ''), left(m.content, 80)) as title,
                               coalesce(jsonb_agg(mt.knowledge_entry_id order by mt.knowledge_entry_id)
                                   filter (where mt.knowledge_entry_id is not null), '[]'::jsonb)::text as target_refs,
                               greatest(similarity(lower(m.content), lower(:query)),
                                        similarity(lower(coalesce(m.metadata_json ->> 'title', '')), lower(:query)))
                                 + case m.metadata_json ->> 'category'
                                     when 'constraint' then 0.30
                                     when 'convention' then 0.20
                                     when 'lesson' then 0.10
                                     else 0
                                   end
                                 + case when bool_or(mt.knowledge_entry_id in (:targets)) then 20 else 0 end
                                   as relevance
                        from memory_items m
                        left join memory_targets mt on mt.memory_id = m.memory_id
                        where m.system_id = :systemId
                          and m.status = 'approved'
                          and m.audience in (:phase, 'both')
                        group by m.memory_id, m.audience, m.content, m.source_ref, m.metadata_json, m.created_at
                        order by relevance desc, m.created_at desc, m.memory_id
                        limit 20
                        """)
                .param("query", query.searchText())
                .param("targets", targets)
                .param("systemId", query.systemId())
                .param("phase", query.phase())
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(
                            "MEM:" + rs.getString("memory_id"), type(), rs.getString("audience"),
                            rs.getString("title"), content, readList(rs.getString("target_refs")),
                            rs.getString("source_ref"),
                            ContextHash.sha256(content), rs.getDouble("relevance"));
                })
                .list();
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
