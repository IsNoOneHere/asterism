package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SystemKnowledgeContextSource implements ContextSource {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public SystemKnowledgeContextSource(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "system_knowledge";
    }

    @Override
    public List<ContextItem> recall(ContextRecallQuery query) {
        var targets = query.targetRefs().isEmpty() ? List.of("") : query.targetRefs();
        return jdbc.sql("""
                        with ranked as (
                            select entry_id, repo_id, kind, title, anchor_texts, route_path,
                                   api_endpoints::text, code_refs::text, source_ref, created_at,
                                   greatest(similarity(lower(title), lower(:query)),
                                            similarity(lower(anchor_texts), lower(:query)),
                                            similarity(lower(route_path), lower(:query)),
                                            similarity(lower(api_endpoints::text), lower(:query)))
                                     + case when entry_id in (:targets) then 10 else 0 end as relevance
                            from system_knowledge
                            where system_id = :systemId and status = 'approved'
                        )
                        select * from ranked where relevance > 0
                        order by relevance desc, created_at desc, entry_id
                        limit 12
                        """)
                .param("query", query.searchText())
                .param("targets", targets)
                .param("systemId", query.systemId())
                .query((rs, rowNum) -> {
                    var entryId = rs.getString("entry_id");
                    var content = content(
                            rs.getString("kind"), rs.getString("title"), rs.getString("route_path"),
                            readList(rs.getString("api_endpoints")), readList(rs.getString("code_refs")));
                    return new ContextItem(
                            "KN:" + entryId, type(), "both", rs.getString("title"), content,
                            List.of(entryId), rs.getString("source_ref"), ContextHash.sha256(content),
                            rs.getDouble("relevance"));
                })
                .list();
    }

    static String content(String kind, String title, String route, List<String> apis, List<String> codeRefs) {
        return "类型: " + kind + "\n标题: " + title
                + (route == null || route.isBlank() ? "" : "\n路由: " + route)
                + (apis.isEmpty() ? "" : "\n接口: " + String.join("、", apis))
                + (codeRefs.isEmpty() ? "" : "\n代码位置: " + String.join("、", codeRefs));
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
