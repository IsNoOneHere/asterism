package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class KnowledgeContextReferenceValidator implements ContextReferenceValidator {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeContextReferenceValidator(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "system_knowledge";
    }

    @Override
    public Optional<ContextItem> current(ContextItem item) {
        var entryId = item.refId().substring("KN:".length());
        return jdbc.sql("""
                        select kind, title, route_path, api_endpoints::text, code_refs::text, source_ref
                        from system_knowledge where entry_id = :id and status = 'approved'
                        """)
                .param("id", entryId)
                .query((rs, rowNum) -> {
                    var content = SystemKnowledgeContextSource.content(
                            rs.getString("kind"), rs.getString("title"), rs.getString("route_path"),
                            readList(rs.getString("api_endpoints")), readList(rs.getString("code_refs")));
                    return new ContextItem(item.refId(), type(), "both", rs.getString("title"), content,
                            List.of(entryId), rs.getString("source_ref"), ContextHash.sha256(content), item.relevance());
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
