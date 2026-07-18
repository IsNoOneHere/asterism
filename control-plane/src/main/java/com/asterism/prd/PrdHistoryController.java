package com.asterism.prd;

import com.asterism.identity.JdbcUserAccountService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v5/prd-sessions")
public class PrdHistoryController {
    private final PrdSessionRepository sessions;
    private final SystemAccessService access;
    private final JdbcUserAccountService users;
    private final WorkItemProjectionRepository workItems;
    private final ObjectMapper objectMapper;

    public PrdHistoryController(PrdSessionRepository sessions, SystemAccessService access,
                                JdbcUserAccountService users, WorkItemProjectionRepository workItems,
                                ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.access = access;
        this.users = users;
        this.workItems = workItems;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    List<PrdSessionView> list(@RequestParam String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        var displayNames = displayNames();
        return sessions.findBySystemIdOrderByUpdatedAtDesc(systemId).stream()
                .map(session -> view(session, displayNames.get(session.createdBy()), canDelete(session, actor)))
                .toList();
    }

    @GetMapping("/{prdId}")
    PrdSessionView detail(@PathVariable String prdId, Authentication actor) {
        var session = sessions.findById(prdId).orElseThrow(() -> new IllegalArgumentException("PRD 不存在"));
        access.requireMember(session.systemId(), actor);
        return view(session, displayNames().get(session.createdBy()), canDelete(session, actor));
    }

    private PrdSessionView view(PrdSession session, String creatorDisplayName, boolean canDelete) {
        return new PrdSessionView(session.prdId(), session.systemId(), session.conversationId(), displayWorkItemId(session.workItemId()),
                session.caseId(), session.title(), session.goal(), readMap(session.draftJson()), readList(session.missingFields()),
                session.status(), session.createdBy(), creatorDisplayName, canDelete, session.createdAt(), session.updatedAt());
    }

    private boolean canDelete(PrdSession session, Authentication actor) {
        return actor.getName().equals(session.createdBy()) || access.canControl(session.systemId(), actor);
    }

    private String displayWorkItemId(String workItemId) {
        if (workItemId == null) return null;
        return workItems.findById(workItemId).map(item -> item.displayWorkItemId()).orElse(workItemId);
    }

    private Map<String, String> displayNames() {
        // 显示名只做友好展示，历史账号不存在时由前端回退到 createdBy。
        return users.listUsers().stream().collect(java.util.stream.Collectors.toMap(
                user -> user.userId(), user -> user.displayName(), (first, ignored) -> first));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    public record PrdSessionView(String prdId, String systemId, String conversationId, String workItemId,
                                 String caseId, String title, String goal, Map<String, Object> draft,
                                 List<String> missingFields, String status, String createdBy,
                                 String creatorDisplayName, boolean canDelete, Instant createdAt, Instant updatedAt) {
    }
}
