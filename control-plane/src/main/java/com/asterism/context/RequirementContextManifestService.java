package com.asterism.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RequirementContextManifestService {
    private static final Logger log = LoggerFactory.getLogger(RequirementContextManifestService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ContextRecallService recall;
    private final Map<String, ContextReferenceValidator> validators;

    public RequirementContextManifestService(JdbcClient jdbc, ObjectMapper objectMapper,
                                             ContextRecallService recall,
                                             List<ContextReferenceValidator> validators) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.recall = recall;
        this.validators = validators.stream().collect(Collectors.toUnmodifiableMap(
                ContextReferenceValidator::type, Function.identity()));
    }

    public String freeze(String systemId, String prdId, String workItemId, List<String> usedRefs,
                         String draftJson, String actorId) {
        var existing = requirementManifestId(prdId);
        if (existing != null && !existing.isBlank()) return existing;
        var available = referencedItems(prdId);
        var selected = usedRefs.stream().distinct()
                .map(ref -> {
                    var item = available.get(ref);
                    if (item == null) throw new IllegalArgumentException("PRD 引用缺少上下文快照: " + ref);
                    return item;
                })
                .toList();
        var queryHash = ContextHash.sha256(draftJson + "|" + usedRefs);
        var manifestId = insert(systemId, prdId, workItemId, queryHash, selected, actorId);
        jdbc.sql("update prd_sessions set requirement_manifest_id = :manifestId where prd_id = :prdId")
                .param("manifestId", manifestId)
                .param("prdId", prdId)
                .update();
        log.info("需求上下文已冻结 system={} prdId={} manifestId={} refs={}",
                systemId, prdId, manifestId, selected.size());
        return manifestId;
    }

    public ExecutionContextSnapshot executionSnapshot(String systemId, String prdId, String workItemId,
                                                      String manifestId, String goal,
                                                      Map<String, Object> draft) {
        var manifest = require(manifestId, systemId, prdId, workItemId);
        var stale = manifest.items().stream()
                .filter(item -> {
                    var validator = validators.get(item.type());
                    return validator == null || !validator.isCurrent(item);
                })
                .map(ContextItem::refId)
                .toList();
        if (!stale.isEmpty()) {
            log.warn("需求上下文已失效 system={} prdId={} manifestId={} refs={}",
                    systemId, prdId, manifestId, stale.size());
            return new ExecutionContextSnapshot(systemId, manifestId, manifest.items(), null, List.of(), stale);
        }
        var targetRefs = manifest.items().stream().flatMap(item -> item.targetRefs().stream()).distinct().toList();
        var execution = recall.recall(new ContextRecallQuery(
                systemId, prdId, "execution", goal, null, draft, targetRefs, List.of(), "worker"));
        var frozenRefs = manifest.items().stream().map(ContextItem::refId).collect(Collectors.toSet());
        var executionItems = execution.items().stream().filter(item -> !frozenRefs.contains(item.refId())).toList();
        return new ExecutionContextSnapshot(systemId, manifestId, manifest.items(), execution.bundleId(),
                executionItems, List.of());
    }

    public String requirementManifestId(String prdId) {
        return jdbc.sql("select requirement_manifest_id from prd_sessions where prd_id = :prdId")
                .param("prdId", prdId)
                .query((rs, rowNum) -> rs.getString("requirement_manifest_id"))
                .optional()
                .orElse(null);
    }

    public List<ContextItem> requirementItems(
            String manifestId, String systemId, String prdId, String workItemId) {
        return require(manifestId, systemId, prdId, workItemId).items();
    }

    public String refresh(String systemId, String prdId, String workItemId, String actorId, String refreshKey) {
        var queryHash = ContextHash.sha256("refresh|" + refreshKey);
        var existing = findByQueryHash(prdId, queryHash);
        if (existing != null) return existing;
        var currentId = requirementManifestId(prdId);
        if (currentId == null || currentId.isBlank()) {
            throw new IllegalStateException("PRD 尚未冻结需求上下文");
        }
        var current = require(currentId, systemId, prdId, workItemId);
        var refreshed = new ArrayList<ContextItem>();
        var removed = new ArrayList<String>();
        for (var item : current.items()) {
            var validator = validators.get(item.type());
            var latest = validator == null ? java.util.Optional.<ContextItem>empty() : validator.current(item);
            if (latest.isPresent()) refreshed.add(latest.get());
            else removed.add(item.refId());
        }
        var manifestId = insert(systemId, prdId, workItemId, queryHash, refreshed, actorId);
        jdbc.sql("update prd_sessions set requirement_manifest_id = :manifestId where prd_id = :prdId")
                .param("manifestId", manifestId)
                .param("prdId", prdId)
                .update();
        log.info("需求上下文已显式刷新 system={} prdId={} manifestId={} refs={} removed={}",
                systemId, prdId, manifestId, refreshed.size(), removed.size());
        return manifestId;
    }

    private String insert(String systemId, String prdId, String workItemId, String queryHash,
                          List<ContextItem> items, String actorId) {
        var manifestId = "manifest-" + UUID.randomUUID();
        jdbc.sql("""
                        insert into context_manifests(
                            manifest_id, system_id, prd_id, work_item_id, phase, query_hash,
                            items_json, created_by, created_at)
                        values (:manifestId, :systemId, :prdId, :workItemId, 'requirement', :queryHash,
                            cast(:items as jsonb), :actorId, :createdAt)
                        """)
                .param("manifestId", manifestId)
                .param("systemId", systemId)
                .param("prdId", prdId)
                .param("workItemId", workItemId)
                .param("queryHash", queryHash)
                .param("items", json(items))
                .param("actorId", actorId)
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return manifestId;
    }

    private String findByQueryHash(String prdId, String queryHash) {
        return jdbc.sql("""
                        select manifest_id from context_manifests
                        where prd_id = :prdId and phase = 'requirement' and query_hash = :queryHash
                        """)
                .param("prdId", prdId)
                .param("queryHash", queryHash)
                .query((rs, rowNum) -> rs.getString("manifest_id"))
                .optional()
                .orElse(null);
    }

    private Manifest require(String manifestId, String systemId, String prdId, String workItemId) {
        return jdbc.sql("""
                        select manifest_id, system_id, prd_id, work_item_id, query_hash, items_json::text
                        from context_manifests
                        where manifest_id = :manifestId and phase = 'requirement'
                        """)
                .param("manifestId", manifestId)
                .query((rs, rowNum) -> new Manifest(
                        rs.getString("manifest_id"), rs.getString("system_id"), rs.getString("prd_id"),
                        rs.getString("work_item_id"), rs.getString("query_hash"), readItems(rs.getString("items_json"))))
                .optional()
                .filter(value -> value.systemId().equals(systemId) && value.prdId().equals(prdId)
                        && value.workItemId().equals(workItemId))
                .orElseThrow(() -> new IllegalArgumentException("需求上下文清单不存在或不属于当前 PRD"));
    }

    private Map<String, ContextItem> referencedItems(String prdId) {
        var result = new LinkedHashMap<String, ContextItem>();
        var payloads = jdbc.sql("""
                        select cb.items_json::text
                        from conversation_messages cm
                        join context_bundles cb on cb.bundle_id = cm.context_bundle_id
                        where cm.prd_id = :prdId
                        order by cm.created_at desc
                        """)
                .param("prdId", prdId)
                .query(String.class)
                .list();
        for (var payload : payloads) {
            for (var item : readItems(payload)) result.putIfAbsent(item.refId(), item);
        }
        var messages = jdbc.sql("""
                        select message_id, content from conversation_messages
                        where prd_id = :prdId and sender_type = 'user'
                        """)
                .param("prdId", prdId)
                .query((rs, rowNum) -> Map.entry(rs.getString("message_id"), rs.getString("content")))
                .list();
        for (var message : messages) {
            var ref = "MSG:" + message.getKey();
            result.putIfAbsent(ref, new ContextItem(ref, "user_message", "product", "用户输入",
                    message.getValue(), List.of(), prdId, ContextHash.sha256(message.getValue()), 10.0));
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("需求上下文清单不是合法 JSON", error);
        }
    }

    private List<ContextItem> readItems(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("需求上下文清单不是合法 JSON", error);
        }
    }

    private record Manifest(String manifestId, String systemId, String prdId, String workItemId, String queryHash,
                            List<ContextItem> items) {
    }

    public record ExecutionContextSnapshot(
            String systemId,
            String requirementManifestId,
            List<ContextItem> requirementItems,
            String executionBundleId,
            List<ContextItem> executionItems,
            List<String> staleReferences) {
    }
}
