package com.asterism.prd;

import com.asterism.knowledge.KnowledgeMatchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrdDraftCodec {
    private final ObjectMapper objectMapper;

    public PrdDraftCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PrdDraft read(String json) {
        try {
            return fromMap(objectMapper.readValue(json, new TypeReference<>() {}));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PRD draft 不是合法 JSON", error);
        }
    }

    public PrdDraft fromMap(Map<String, Object> value) {
        var fields = new LinkedHashMap<>(value == null ? Map.of() : value);
        var title = text(fields.remove("title"));
        var goal = text(fields.remove("goal"));
        var scope = text(fields.remove("scope"));
        var acceptanceCriteria = strings(fields.remove("acceptanceCriteria"));
        var suspectedTargets = targets(fields.remove("suspectedTargets"));
        var targets = targets(fields.remove("targets"));
        return new PrdDraft(title, goal, scope, acceptanceCriteria, suspectedTargets, targets, fields);
    }

    public Map<String, Object> toMap(PrdDraft draft) {
        var value = new LinkedHashMap<>(draft.extras());
        if (draft.title() != null) value.put("title", draft.title());
        if (draft.goal() != null) value.put("goal", draft.goal());
        if (draft.scope() != null) value.put("scope", draft.scope());
        value.put("acceptanceCriteria", draft.acceptanceCriteria());
        if (!draft.suspectedTargets().isEmpty()) value.put("suspectedTargets", targetMaps(draft.suspectedTargets()));
        if (!draft.targets().isEmpty()) value.put("targets", targetMaps(draft.targets()));
        return value;
    }

    public String write(PrdDraft draft) {
        try {
            return objectMapper.writeValueAsString(toMap(draft));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("PRD draft 不是合法 JSON", error);
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> strings(Object value) {
        return value == null ? List.of() : objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<KnowledgeMatchService.SuspectedTarget> targets(Object value) {
        return value == null ? List.of() : objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<Map<String, Object>> targetMaps(List<KnowledgeMatchService.SuspectedTarget> value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }
}
