package com.asterism.knowledge;

import com.asterism.event.DomainEventRecord;
import com.asterism.prd.ConversationMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WorkItemKnowledgeLearningService {
    private final ConversationMessageRepository messages;
    private final SystemKnowledgeService knowledge;
    private final ObjectMapper objectMapper;

    public WorkItemKnowledgeLearningService(ConversationMessageRepository messages,
                                            SystemKnowledgeService knowledge, ObjectMapper objectMapper) {
        this.messages = messages;
        this.knowledge = knowledge;
        this.objectMapper = objectMapper;
    }

    public void learn(DomainEventRecord event) {
        if (!"ReleaseCompleted".equals(event.eventType()) || event.prdId() == null) return;
        var anchors = new ArrayList<String>();
        for (var message : messages.findByPrdIdOrderByCreatedAtAsc(event.prdId())) {
            for (var observation : readObjects(message.observationsJson())) {
                add(anchors, observation.get("page_title"));
                add(anchors, observation.get("pageTitle"));
                addAll(anchors, observation.get("text_anchors"));
                addAll(anchors, observation.get("textAnchors"));
                addAll(anchors, observation.get("error_messages"));
                addAll(anchors, observation.get("errorMessages"));
            }
        }
        if (anchors.isEmpty()) return;
        var payload = readMap(event.payloadJson());
        var candidates = new ArrayList<SystemKnowledgeService.CandidateRequest>();
        if (payload.get("repositories") instanceof List<?> repositories) {
            for (var value : repositories) {
                if (!(value instanceof Map<?, ?> repo)) continue;
                candidates.add(candidate(repo.get("repo") == null ? "main" : String.valueOf(repo.get("repo")), anchors,
                        stringList(repo.get("changedPaths")), event.workItemId()));
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(candidate(String.valueOf(payload.getOrDefault("repo", "main")), anchors,
                    stringList(payload.getOrDefault("changedPaths", payload.get("changed_paths"))), event.workItemId()));
        }
        knowledge.writeCandidates(event.systemId(), candidates, "work_item_learning", "asterism-worker");
    }

    private SystemKnowledgeService.CandidateRequest candidate(String repo, List<String> anchors,
                                                               List<String> changedPaths, String workItemId) {
        return new SystemKnowledgeService.CandidateRequest(repo, "page", anchors.getFirst(), List.copyOf(anchors),
                "", List.of(), changedPaths, workItemId);
    }

    private void add(List<String> target, Object value) {
        if (value instanceof String text && !text.isBlank() && !target.contains(text)) target.add(text);
    }

    private void addAll(List<String> target, Object value) {
        for (var item : stringList(value)) add(target, item);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<Map<String, Object>> readObjects(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }
}
