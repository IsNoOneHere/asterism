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
        if (query.memoryTypes().isEmpty()) return List.of();
        var targets = query.targetRefs().isEmpty() ? List.of("") : query.targetRefs();
        var artifactSourceIds = query.artifactSourceIds().isEmpty()
                ? List.of("") : query.artifactSourceIds();
        var memoryTypes = query.memoryTypes().stream().map(Enum::name).toList();
        return jdbc.sql("""
                        select m.memory_id, m.content, m.artifact_source_id, m.title,
                               coalesce(jsonb_agg(mt.knowledge_entry_id order by mt.knowledge_entry_id)
                                   filter (where mt.knowledge_entry_id is not null), '[]'::jsonb)::text as target_refs,
                               greatest(similarity(lower(m.content), lower(:query)),
                                        similarity(lower(m.title), lower(:query)))
                                 + case m.memory_type
                                     when 'CONSTRAINT' then 0.30
                                     when 'DECISION' then 0.20
                                     when 'EXPERIENCE' then 0.10
                                     when 'FACT' then 0.10
                                     else 0
                                   end
                                 + m.confidence * 0.20
                                 + case when bool_or(mt.knowledge_entry_id in (:targets)) then 20 else 0 end
                                   as relevance
                        from memory_items m
                        left join memory_targets mt on mt.memory_id = m.memory_id
                        join artifacts source on source.artifact_id = m.artifact_source_id
                        where m.project_scope = :projectScope
                          and m.system_id = :systemId
                          and m.status = 'ACTIVE'
                          and m.memory_type in (:memoryTypes)
                          and (m.expires_at is null or m.expires_at > now())
                          and (
                            m.applicability = 'PROJECT'
                            or m.artifact_source_id in (:artifactSourceIds)
                          )
                          and source.status <> 'SUPERSEDED'
                          and not exists (
                            select 1 from artifacts replacement
                            where replacement.supersedes_artifact_id = source.artifact_id
                              and replacement.status = 'APPROVED'
                          )
                        group by m.memory_id, m.content, m.artifact_source_id, m.title,
                                 m.memory_type, m.confidence, m.created_at
                        order by relevance desc, m.created_at desc, m.memory_id
                        limit 20
                        """)
                .param("query", query.searchText())
                .param("targets", targets)
                .param("memoryTypes", memoryTypes)
                .param("artifactSourceIds", artifactSourceIds)
                .param("systemId", query.systemId())
                .param("projectScope", query.projectScope())
                .query((rs, rowNum) -> {
                    var content = rs.getString("content");
                    return new ContextItem(
                            "MEM:" + rs.getString("memory_id"), type(), "both",
                            rs.getString("title"), content, readList(rs.getString("target_refs")),
                            rs.getString("artifact_source_id"),
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
