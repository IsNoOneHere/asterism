package com.asterism.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class SystemKnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(SystemKnowledgeService.class);
    private static final Set<String> KINDS = Set.of("route", "page", "api");
    private static final Set<String> STATUSES = Set.of("candidate", "approved", "rejected", "disabled");
    private final SystemKnowledgeRepository entries;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;

    public SystemKnowledgeService(SystemKnowledgeRepository entries, JdbcAggregateTemplate aggregate,
                                  ObjectMapper objectMapper) {
        this.entries = entries;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeView> list(String systemId, String status) {
        var values = status == null || status.isBlank()
                ? entries.findBySystemIdOrderByCreatedAtDesc(systemId)
                : entries.findBySystemIdAndStatusOrderByCreatedAtDesc(systemId, status);
        return values.stream().map(this::view).toList();
    }

    // 知识库列表只返回当前页，避免索引条目增多后一次传输超大 JSON。
    public KnowledgePageView listPage(String systemId, String status, String query, int page, int pageSize) {
        var safePageSize = Math.min(Math.max(pageSize, 1), 50);
        var keyword = "%" + text(query).trim().toLowerCase(Locale.ROOT) + "%";
        var total = entries.countPage(systemId, status, keyword);
        var totalPages = Math.max(1, (int) ((total + safePageSize - 1) / safePageSize));
        var safePage = Math.min(Math.max(page, 1), totalPages);
        var values = entries.findPage(systemId, status, keyword, safePageSize,
                (long) (safePage - 1) * safePageSize);
        return new KnowledgePageView(values.stream().map(this::view).toList(), total, safePage,
                safePageSize, totalPages);
    }

    public KnowledgeView createManual(String systemId, CandidateRequest request, String actor) {
        return view(insert(systemId, request, "manual", actor));
    }

    public int writeCandidates(String systemId, List<CandidateRequest> candidates, String source, String actor) {
        var created = 0;
        for (var candidate : candidates) {
            if (exists(systemId, candidate, source)) continue;
            try {
                insert(systemId, candidate, source, actor);
                created++;
            } catch (DataIntegrityViolationException ignored) {
                // 多个索引 workflow 并发回调时由唯一索引完成最终幂等。
            }
        }
        log.info("系统知识 candidate 已写入 system={} source={} count={}", systemId, source, created);
        return created;
    }

    public KnowledgeView updateStatus(String systemId, String entryId, String status, String actor) {
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("不支持的知识状态: " + status);
        var current = require(systemId, entryId);
        var approved = "approved".equals(status);
        var updated = new SystemKnowledge(
                current.entryId(), current.systemId(), current.repoId(), current.kind(), current.title(), current.anchorTexts(),
                current.routePath(), current.apiEndpoints(), current.codeRefs(), status, current.source(),
                current.sourceRef(), current.createdBy(), current.createdAt(), approved ? actor : null,
                approved ? Instant.now() : null);
        aggregate.update(updated);
        log.info("系统知识状态已更新 system={} entryId={} status={}", systemId, entryId, status);
        return view(updated);
    }

    public SystemKnowledge require(String systemId, String entryId) {
        var entry = entries.findById(entryId).orElseThrow(() -> new IllegalArgumentException("知识条目不存在"));
        if (!entry.systemId().equals(systemId)) throw new IllegalArgumentException("知识条目不属于当前系统");
        return entry;
    }

    private SystemKnowledge insert(String systemId, CandidateRequest request, String source, String actor) {
        if (!KINDS.contains(request.kind())) throw new IllegalArgumentException("不支持的知识类型: " + request.kind());
        return aggregate.insert(new SystemKnowledge(
                "knowledge-" + UUID.randomUUID(), systemId, text(request.repo()).isBlank() ? "main" : request.repo(),
                request.kind(), request.title(),
                String.join("\n", list(request.anchorTexts())), text(request.routePath()),
                json(list(request.apiEndpoints())), json(list(request.codeRefs())), "candidate", source,
                text(request.sourceRef()), actor, Instant.now(), null, null));
    }

    private boolean exists(String systemId, CandidateRequest request, String source) {
        var repo = text(request.repo()).isBlank() ? "main" : request.repo();
        if (request.routePath() != null && !request.routePath().isBlank()
                && entries.findBySystemIdAndRepoIdAndRoutePath(systemId, repo, request.routePath()).isPresent()) return true;
        return request.sourceRef() != null && !request.sourceRef().isBlank()
                && entries.findBySystemIdAndRepoIdAndSourceAndSourceRef(systemId, repo, source, request.sourceRef()).isPresent();
    }

    private KnowledgeView view(SystemKnowledge entry) {
        return new KnowledgeView(entry.entryId(), entry.systemId(), entry.repoId(), entry.kind(), entry.title(),
                entry.anchorTexts().lines().filter(value -> !value.isBlank()).toList(), entry.routePath(),
                readList(entry.apiEndpoints()), readList(entry.codeRefs()), entry.status(), entry.source(),
                entry.sourceRef(), entry.createdBy(), entry.createdAt(), entry.approvedBy(), entry.approvedAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("系统知识不是合法 JSON", error);
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private List<String> list(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record CandidateRequest(String repo, String kind, String title, List<String> anchorTexts, String routePath,
                                   List<String> apiEndpoints, List<String> codeRefs, String sourceRef) {
        public CandidateRequest(String kind, String title, List<String> anchorTexts, String routePath,
                                List<String> apiEndpoints, List<String> codeRefs, String sourceRef) {
            this("main", kind, title, anchorTexts, routePath, apiEndpoints, codeRefs, sourceRef);
        }
    }

    public record KnowledgeView(String entryId, String systemId, String repo, String kind, String title,
                                List<String> anchorTexts, String routePath, List<String> apiEndpoints,
                                List<String> codeRefs, String status, String source, String sourceRef,
                                String createdBy, Instant createdAt, String approvedBy, Instant approvedAt) {
    }

    public record KnowledgePageView(List<KnowledgeView> items, long total, int page, int pageSize, int totalPages) {
    }
}
