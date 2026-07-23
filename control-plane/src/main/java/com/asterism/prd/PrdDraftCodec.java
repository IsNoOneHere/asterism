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
        var acceptanceCriteria = strings("acceptanceCriteria", fields.remove("acceptanceCriteria"));
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

    private List<String> strings(String field, Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) {
            throw new DraftFieldTypeException(field, "List<String>", value.getClass().getName());
        }
        for (var index = 0; index < values.size(); index++) {
            var item = values.get(index);
            if (!(item instanceof String)) {
                var actualType = item == null ? "null" : item.getClass().getName();
                throw new DraftFieldTypeException(field + "[" + index + "]", "String", actualType);
            }
        }
        return values.stream().map(String.class::cast).toList();
    }

    private List<KnowledgeMatchService.SuspectedTarget> targets(Object value) {
        return value == null ? List.of() : objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private List<Map<String, Object>> targetMaps(List<KnowledgeMatchService.SuspectedTarget> value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    /** 精确标识跨服务字段和实际类型，避免 Jackson 裸异常掩盖契约漂移。 */
    public static final class DraftFieldTypeException extends IllegalArgumentException {
        private final String field;
        private final String expectedType;
        private final String actualType;

        public DraftFieldTypeException(String field, String expectedType, String actualType) {
            super("PRD draft 字段 " + field + " 类型错误，期望 " + expectedType + "，实际 " + actualType);
            this.field = field;
            this.expectedType = expectedType;
            this.actualType = actualType;
        }

        public String field() {
            return field;
        }

        public String expectedType() {
            return expectedType;
        }

        public String actualType() {
            return actualType;
        }
    }
}
