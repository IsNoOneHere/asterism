package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ContextBundleStore {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ContextBundleStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void save(ContextBundle bundle, String actorId) {
        jdbc.sql("""
                        insert into context_bundles(
                            bundle_id, system_id, prd_id, phase, query_hash, items_json, created_by, created_at)
                        values (:bundleId, :systemId, :prdId, :phase, :queryHash,
                            cast(:items as jsonb), :actorId, :createdAt)
                        """)
                .param("bundleId", bundle.bundleId())
                .param("systemId", bundle.systemId())
                .param("prdId", bundle.prdId())
                .param("phase", bundle.phase())
                .param("queryHash", bundle.queryHash())
                .param("items", json(bundle.items()))
                .param("actorId", actorId)
                .param("createdAt", bundle.createdAt())
                .update();
    }

    public Optional<ContextBundle> find(String bundleId) {
        return jdbc.sql("""
                        select bundle_id, system_id, prd_id, phase, query_hash, items_json::text, created_at
                        from context_bundles where bundle_id = :bundleId
                        """)
                .param("bundleId", bundleId)
                .query((rs, rowNum) -> new ContextBundle(
                        rs.getString("bundle_id"), rs.getString("system_id"), rs.getString("prd_id"),
                        rs.getString("phase"), rs.getString("query_hash"), readItems(rs.getString("items_json")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("ContextBundle 不是合法 JSON", error);
        }
    }

    private List<ContextItem> readItems(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("ContextBundle 不是合法 JSON", error);
        }
    }
}
