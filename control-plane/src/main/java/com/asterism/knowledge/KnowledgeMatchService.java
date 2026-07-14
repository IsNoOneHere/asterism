package com.asterism.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeMatchService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeMatchService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public MatchResult match(String systemId, List<String> anchors) {
        var approvedCount = jdbc.sql("select count(*) from system_knowledge where system_id = :systemId and status = 'approved'")
                .param("systemId", systemId).query(Long.class).single();
        if (approvedCount == 0 || anchors == null || anchors.isEmpty()) return new MatchResult(List.of(), approvedCount == 0);
        var query = String.join(" ", anchors);
        var targets = jdbc.sql("""
                        select entry_id, kind, title, route_path, api_endpoints::text, code_refs::text,
                               greatest(similarity(title, :query), similarity(anchor_texts, :query)) as confidence
                        from system_knowledge
                        where system_id = :systemId and status = 'approved'
                        order by confidence desc, created_at desc
                        limit 3
                        """)
                .param("query", query)
                .param("systemId", systemId)
                .query((rs, rowNum) -> new SuspectedTarget(
                        rs.getString("entry_id"), rs.getString("kind"), rs.getString("title"),
                        rs.getString("route_path"), readList(rs.getString("api_endpoints")),
                        readList(rs.getString("code_refs")), rs.getDouble("confidence")))
                .list();
        return new MatchResult(targets, false);
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    public record SuspectedTarget(String entryId, String kind, String title, String routePath,
                                  List<String> apiEndpoints, List<String> codeRefs, double confidence) {
    }

    public record MatchResult(List<SuspectedTarget> targets, boolean knowledgeEmpty) {
    }
}
