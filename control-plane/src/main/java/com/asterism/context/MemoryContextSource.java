package com.asterism.context;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryContextSource implements ContextSource {
    private final JdbcClient jdbc;

    public MemoryContextSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public List<ContextItem> recall(ContextRecallQuery query) {
        return jdbc.sql("""
                        select memory_id, audience, content, source_event_id,
                               coalesce(nullif(metadata_json ->> 'title', ''), left(content, 80)) as title,
                               greatest(similarity(lower(content), lower(:query)),
                                        similarity(lower(coalesce(metadata_json ->> 'title', '')), lower(:query)))
                                 + case metadata_json ->> 'category'
                                     when 'constraint' then 0.30
                                     when 'convention' then 0.20
                                     when 'lesson' then 0.10
                                     else 0
                                   end as relevance
                        from memory_items
                        where system_id = :systemId
                          and status = 'approved'
                          and audience in (:phase, 'both')
                        order by relevance desc, created_at desc, memory_id
                        limit 20
                        """)
                .param("query", query.searchText())
                .param("systemId", query.systemId())
                .param("phase", query.phase())
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(
                            "MEM:" + rs.getString("memory_id"), type(), rs.getString("audience"),
                            rs.getString("title"), content, List.of(), rs.getString("source_event_id"),
                            ContextHash.sha256(content), rs.getDouble("relevance"));
                })
                .list();
    }
}
