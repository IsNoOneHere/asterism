package com.asterism.memory;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.prd.PrdDraftCodec;
import com.asterism.prd.PrdSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkItemMemoryLearningService {
    private static final Logger log = LoggerFactory.getLogger(WorkItemMemoryLearningService.class);
    private static final List<String> REUSABLE_HINTS = List.of(
            "兼容", "必须", "统一", "复用", "约束", "规则", "规范", "不能", "禁止", "保持", "原因");

    private final DomainEventService events;
    private final PrdSessionRepository sessions;
    private final PrdDraftCodec drafts;
    private final MemoryCandidateService candidates;
    private final ObjectMapper objectMapper;

    public WorkItemMemoryLearningService(DomainEventService events, PrdSessionRepository sessions,
                                         PrdDraftCodec drafts, MemoryCandidateService candidates,
                                         ObjectMapper objectMapper) {
        this.events = events;
        this.sessions = sessions;
        this.drafts = drafts;
        this.candidates = candidates;
        this.objectMapper = objectMapper;
    }

    public void learn(DomainEventRecord release) {
        if (!"ReleaseCompleted".equals(release.eventType()) || release.workItemId() == null) return;
        var targets = release.prdId() == null ? List.<String>of() : sessions.findById(release.prdId())
                .map(session -> drafts.read(session.draftJson()).targets().stream()
                        .map(target -> target.entryId()).toList())
                .orElse(List.of());
        var inputs = new ArrayList<MemoryCandidateService.CandidateInput>();
        for (var event : events.findByWorkItemId(release.workItemId())) {
            if (!"RevisionRequested".equals(event.eventType())) continue;
            var payload = readMap(event.payloadJson());
            var note = text(payload.get("note")).trim();
            if (!isReusable(note)) continue;
            var sourceRef = "work-item:" + release.workItemId() + ":" + event.eventId();
            inputs.add(new MemoryCandidateService.CandidateInput(
                    release.systemId(), "lesson", "execution", lessonTitle(payload), note, sourceRef,
                    targets, List.of(event.eventId(), release.eventId()), release.workItemId(),
                    event.eventId(), "asterism-worker"));
        }
        var created = candidates.createAll(inputs);
        log.info("发布完成候选记忆已提取 workItem={} revisions={} candidates={}",
                release.workItemId(), inputs.size(), created.size());
    }

    private String lessonTitle(Map<String, Object> payload) {
        var phase = text(payload.get("phase"));
        return phase.isBlank() ? "人工修订经验" : "人工修订经验 · " + phase;
    }

    private boolean isReusable(String note) {
        if (note.length() < 6) return false;
        var normalized = note.toLowerCase(Locale.ROOT);
        return REUSABLE_HINTS.stream().anyMatch(normalized::contains);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
