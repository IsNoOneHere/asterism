package com.agentteam.v5.system;

import com.agentteam.v5.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessModelConfigService {
    static final String LEGACY_MODEL_ID = "legacy-default";
    private static final Logger log = LoggerFactory.getLogger(BusinessModelConfigService.class);

    private final SystemProfileRepository systems;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;
    private final SystemConfigLock lock;

    public BusinessModelConfigService(SystemProfileRepository systems, JdbcAggregateTemplate aggregate,
                                      ObjectMapper objectMapper, SystemConfigLock lock) {
        this.systems = systems;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
        this.lock = lock;
    }

    public BusinessModelPoolResponse get(String systemId) {
        return response(load(systemId));
    }

    @Transactional
    public BusinessModelPoolResponse create(String systemId, BusinessModelRequest request) {
        var state = lockedState(systemId);
        requireUniqueName(state.models(), request.name(), null);
        var model = new BusinessModel("bm-" + UUID.randomUUID(), request.name().trim(), request.preset(),
                request.model(), value(request.baseUrl()), value(request.apiKey()));
        state.models().add(model);
        if (state.routing().defaultModelId().isBlank()) {
            state = state.withRouting(new Routing(model.modelId(), "", "", ""));
        }
        save(state);
        log.info("业务模型已新增 system={} modelId={} name={}", systemId, model.modelId(), model.name());
        return response(state);
    }

    @Transactional
    public BusinessModelPoolResponse update(String systemId, String modelId, BusinessModelRequest request) {
        var state = lockedState(systemId);
        var index = indexOf(state.models(), modelId);
        requireUniqueName(state.models(), request.name(), modelId);
        var current = state.models().get(index);
        var apiKey = request.apiKey() == null || request.apiKey().isBlank() ? current.apiKey() : request.apiKey();
        state.models().set(index, new BusinessModel(modelId, request.name().trim(), request.preset(),
                request.model(), value(request.baseUrl()), value(apiKey)));
        save(state);
        log.info("业务模型已更新 system={} modelId={} name={}", systemId, modelId, request.name());
        return response(state);
    }

    @Transactional
    public BusinessModelPoolResponse delete(String systemId, String modelId) {
        var state = lockedState(systemId);
        indexOf(state.models(), modelId);
        if (references(state, modelId)) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_IN_USE", "模型正在被业务阶段或 Claude Agent 使用");
        }
        state.models().removeIf(model -> model.modelId().equals(modelId));
        save(state);
        log.info("业务模型已删除 system={} modelId={}", systemId, modelId);
        return response(state);
    }

    @Transactional
    public BusinessModelPoolResponse updateRouting(String systemId, RoutingRequest request) {
        var state = lockedState(systemId);
        requireModel(state.models(), request.defaultModelId());
        requireOptionalModel(state.models(), request.prdModelId());
        requireOptionalModel(state.models(), request.planningModelId());
        requireOptionalModel(state.models(), request.diffModelId());
        state = state.withRouting(new Routing(request.defaultModelId(), value(request.prdModelId()),
                value(request.planningModelId()), value(request.diffModelId())));
        save(state);
        log.info("业务模型路由已更新 system={} defaultModelId={}", systemId, request.defaultModelId());
        return response(state);
    }

    @Transactional
    public SystemProfile updateLegacyDefault(String systemId, SystemController.ModelConfigRequest request) {
        var state = lockedState(systemId);
        BusinessModel current;
        if (state.models().isEmpty()) {
            current = new BusinessModel(LEGACY_MODEL_ID, "默认业务模型", request.preset(), request.model(),
                    value(request.baseUrl()), value(request.apiKey()));
            state.models().add(current);
            state = state.withRouting(new Routing(current.modelId(), "", "", ""));
        } else {
            var modelId = effectiveDefault(state);
            var index = indexOf(state.models(), modelId);
            current = state.models().get(index);
            var apiKey = request.apiKey() == null || request.apiKey().isBlank() ? current.apiKey() : request.apiKey();
            state.models().set(index, new BusinessModel(modelId, current.name(), request.preset(), request.model(),
                    value(request.baseUrl()), value(apiKey)));
        }
        return save(state);
    }

    @Transactional
    public SystemProfile updateClaude(String systemId, SystemController.ClaudeModelConfigRequest request) {
        var state = lockedState(systemId);
        var sourceModelId = value(request.businessModelId());
        if (request.reuseBusinessApiKey()) {
            sourceModelId = sourceModelId.isBlank() ? effectiveDefault(state) : sourceModelId;
            requireModel(state.models(), sourceModelId);
        }
        state.config().put("claudePreset", request.preset());
        state.config().put("claudeModel", request.model());
        state.config().put("claudeBaseUrl", value(request.baseUrl()));
        state.config().put("claudeReuseBusinessApiKey", request.reuseBusinessApiKey());
        if (request.reuseBusinessApiKey()) state.config().put("claudeBusinessModelId", sourceModelId);
        else state.config().remove("claudeBusinessModelId");
        if (request.apiKey() != null && !request.apiKey().isBlank()) state.config().put("claudeApiKey", request.apiKey());
        return save(state);
    }

    public ResolvedBusinessModel resolve(String systemId, String stage) {
        return resolve(load(systemId), stage);
    }

    ResolvedBusinessModel resolve(State state, String stage) {
        var configured = !state.models().isEmpty();
        if (!configured) return new ResolvedBusinessModel(false, false, "", "", "", "", "", "");
        var modelId = switch (stage == null ? "default" : stage) {
            case "prd" -> fallback(state.routing().prdModelId(), effectiveDefault(state));
            case "planning" -> fallback(state.routing().planningModelId(), effectiveDefault(state));
            case "diff" -> fallback(state.routing().diffModelId(), effectiveDefault(state));
            case "default" -> effectiveDefault(state);
            default -> throw new IllegalArgumentException("未知模型阶段: " + stage);
        };
        var model = state.models().stream().filter(item -> item.modelId().equals(modelId)).findFirst().orElse(null);
        if (model == null) return new ResolvedBusinessModel(true, false, modelId, "", "", "", "", "");
        var ready = !model.model().isBlank() && !model.apiKey().isBlank();
        return new ResolvedBusinessModel(true, ready, model.modelId(), model.name(), model.preset(),
                model.model(), model.baseUrl(), model.apiKey());
    }

    public ResolvedClaudeKey resolveClaudeKey(String systemId) {
        var state = load(systemId);
        var reuse = booleanValue(state.config().get("claudeReuseBusinessApiKey"));
        if (!reuse) {
            var key = text(state.config().get("claudeApiKey"));
            return new ResolvedClaudeKey(hasClaudeConfig(state.config()), !key.isBlank(), "system", "", key);
        }
        var sourceId = fallback(text(state.config().get("claudeBusinessModelId")), effectiveDefault(state));
        var model = state.models().stream().filter(item -> item.modelId().equals(sourceId)).findFirst().orElse(null);
        return new ResolvedClaudeKey(true, model != null && !model.apiKey().isBlank(), "system_reuse",
                sourceId, model == null ? "" : model.apiKey());
    }

    private State lockedState(String systemId) {
        lock.lockBusinessModels(systemId);
        return load(systemId).materialized();
    }

    private State load(String systemId) {
        var profile = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var config = new LinkedHashMap<>(readMap(profile.modelProviderConfig()));
        var models = readModels(config);
        var routing = readRouting(config);
        if (models.isEmpty() && hasLegacy(config)) {
            models.add(new BusinessModel(LEGACY_MODEL_ID, "默认业务模型", text(config.get("provider")),
                    text(config.get("model")), first(config, "baseUrl", "base_url"), first(config, "apiKey", "api_key")));
            routing = new Routing(LEGACY_MODEL_ID, "", "", "");
        }
        return new State(profile, config, models, routing);
    }

    private List<BusinessModel> readModels(Map<String, Object> config) {
        var result = new ArrayList<BusinessModel>();
        if (!(config.get("businessModels") instanceof List<?> list)) return result;
        for (var item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            var map = new LinkedHashMap<String, Object>();
            raw.forEach((key, value) -> map.put(String.valueOf(key), value));
            result.add(new BusinessModel(text(map.get("modelId")), text(map.get("name")), text(map.get("preset")),
                    text(map.get("model")), text(map.get("baseUrl")), text(map.get("apiKey"))));
        }
        return result;
    }

    private Routing readRouting(Map<String, Object> config) {
        if (!(config.get("businessRouting") instanceof Map<?, ?> raw)) return new Routing("", "", "", "");
        var map = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> map.put(String.valueOf(key), value));
        return new Routing(text(map.get("defaultModelId")), text(map.get("prdModelId")),
                text(map.get("planningModelId")), text(map.get("diffModelId")));
    }

    private BusinessModelPoolResponse response(State state) {
        var models = state.models().stream().map(model -> new BusinessModelView(model.modelId(), model.name(),
                model.preset(), model.model(), model.baseUrl(), !model.apiKey().isBlank())).toList();
        var defaultId = effectiveDefault(state);
        var effective = new Routing(defaultId, fallback(state.routing().prdModelId(), defaultId),
                fallback(state.routing().planningModelId(), defaultId), fallback(state.routing().diffModelId(), defaultId));
        return new BusinessModelPoolResponse(models, state.routing(), effective);
    }

    private SystemProfile save(State state) {
        state.config().put("businessModels", state.models().stream().map(this::modelMap).toList());
        state.config().put("businessRouting", routingMap(state.routing()));
        for (var key : List.of("provider", "model", "baseUrl", "base_url", "apiKey", "api_key")) state.config().remove(key);
        var current = state.profile();
        var saved = aggregate.update(new SystemProfile(current.systemId(), current.name(), current.description(), current.repoPath(),
                current.ownerUserId(), current.allowedPaths(), current.forbiddenPaths(), current.testCommands(), current.agentConfig(),
                json(state.config()), current.createdBy(), current.createdAt(), Instant.now()));
        state.setProfile(saved);
        return saved;
    }

    private Map<String, Object> modelMap(BusinessModel model) {
        var result = new LinkedHashMap<String, Object>();
        result.put("modelId", model.modelId());
        result.put("name", model.name());
        result.put("preset", model.preset());
        result.put("model", model.model());
        result.put("baseUrl", model.baseUrl());
        if (!model.apiKey().isBlank()) result.put("apiKey", model.apiKey());
        return result;
    }

    private Map<String, Object> routingMap(Routing routing) {
        var result = new LinkedHashMap<String, Object>();
        result.put("defaultModelId", routing.defaultModelId());
        if (!routing.prdModelId().isBlank()) result.put("prdModelId", routing.prdModelId());
        if (!routing.planningModelId().isBlank()) result.put("planningModelId", routing.planningModelId());
        if (!routing.diffModelId().isBlank()) result.put("diffModelId", routing.diffModelId());
        return result;
    }

    private boolean references(State state, String modelId) {
        var routing = state.routing();
        if (modelId.equals(routing.defaultModelId()) || modelId.equals(routing.prdModelId())
                || modelId.equals(routing.planningModelId()) || modelId.equals(routing.diffModelId())) return true;
        return booleanValue(state.config().get("claudeReuseBusinessApiKey"))
                && modelId.equals(fallback(text(state.config().get("claudeBusinessModelId")), effectiveDefault(state)));
    }

    private void requireUniqueName(List<BusinessModel> models, String name, String exceptId) {
        var normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("模型配置名称不能为空");
        if (models.stream().anyMatch(model -> model.name().equalsIgnoreCase(normalized) && !model.modelId().equals(exceptId))) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_NAME_EXISTS", "模型配置名称已存在");
        }
    }

    private void requireOptionalModel(List<BusinessModel> models, String modelId) {
        if (modelId != null && !modelId.isBlank()) requireModel(models, modelId);
    }

    private void requireModel(List<BusinessModel> models, String modelId) {
        if (modelId == null || modelId.isBlank() || models.stream().noneMatch(model -> model.modelId().equals(modelId))) {
            throw new IllegalArgumentException("业务模型不存在: " + value(modelId));
        }
    }

    private int indexOf(List<BusinessModel> models, String modelId) {
        for (var index = 0; index < models.size(); index++) if (models.get(index).modelId().equals(modelId)) return index;
        throw new IllegalArgumentException("业务模型不存在: " + modelId);
    }

    private String effectiveDefault(State state) {
        if (!state.routing().defaultModelId().isBlank()) return state.routing().defaultModelId();
        return state.models().isEmpty() ? "" : state.models().getFirst().modelId();
    }

    private boolean hasLegacy(Map<String, Object> config) {
        return List.of("provider", "model", "baseUrl", "base_url", "apiKey", "api_key").stream().anyMatch(config::containsKey);
    }

    private boolean hasClaudeConfig(Map<String, Object> config) {
        return List.of("claudePreset", "claudeModel", "claudeBaseUrl", "claudeApiKey", "claudeReuseBusinessApiKey")
                .stream().anyMatch(config::containsKey);
    }

    private String first(Map<String, Object> config, String camel, String snake) {
        return text(config.containsKey(camel) ? config.get(camel) : config.get(snake));
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("系统模型配置不是合法 JSON", error);
        }
    }

    private record BusinessModel(String modelId, String name, String preset, String model, String baseUrl, String apiKey) {}

    private static final class State {
        private SystemProfile profile;
        private final LinkedHashMap<String, Object> config;
        private final List<BusinessModel> models;
        private final Routing routing;

        private State(SystemProfile profile, LinkedHashMap<String, Object> config, List<BusinessModel> models, Routing routing) {
            this.profile = profile;
            this.config = config;
            this.models = models;
            this.routing = routing;
        }

        State materialized() { return this; }
        State withRouting(Routing value) { return new State(profile, config, models, value); }
        SystemProfile profile() { return profile; }
        void setProfile(SystemProfile value) { profile = value; }
        LinkedHashMap<String, Object> config() { return config; }
        List<BusinessModel> models() { return models; }
        Routing routing() { return routing; }
    }

    public record BusinessModelRequest(@NotBlank String name, @NotBlank String preset, @NotBlank String model,
                                       String baseUrl, String apiKey) {}
    public record RoutingRequest(@NotBlank String defaultModelId, String prdModelId, String planningModelId,
                                 String diffModelId) {}
    public record BusinessModelView(String modelId, String name, String preset, String model, String baseUrl,
                                    boolean apiKeyConfigured) {}
    public record Routing(String defaultModelId, String prdModelId, String planningModelId, String diffModelId) {}
    public record BusinessModelPoolResponse(List<BusinessModelView> models, Routing routing, Routing effectiveRouting) {}
    public record ResolvedBusinessModel(boolean managed, boolean configured, String modelId, String name, String preset,
                                        String model, String baseUrl, String apiKey) {}
    public record ResolvedClaudeKey(boolean managed, boolean configured, String source, String businessModelId,
                                    String apiKey) {}
}
