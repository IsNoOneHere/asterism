package com.agentteam.v5.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ContextManifestService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ContextManifestService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String create(String systemId, String workItemId, List<MemoryItem> approvedMemories) {
        var manifestId = "manifest-" + UUID.randomUUID();
        var refs = approvedMemories.stream().map(MemoryItem::memoryId).toList();
        // 每次 worker 取上下文都落一条 manifest，方便追溯当时召回了哪些记忆。
        jdbc.sql("""
                        insert into context_manifests(
                            manifest_id, system_id, work_item_id,
                            approved_memory_refs, rejected_memory_refs, summary, created_by)
                        values (:manifestId, :systemId, :workItemId,
                            cast(:approvedRefs as jsonb), '[]'::jsonb, :summary, 'worker')
                        """)
                .param("manifestId", manifestId)
                .param("systemId", systemId)
                .param("workItemId", workItemId)
                .param("approvedRefs", json(refs))
                .param("summary", "recall=" + refs.size())
                .update();
        return manifestId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("context manifest 不是合法 JSON", error);
        }
    }
}
