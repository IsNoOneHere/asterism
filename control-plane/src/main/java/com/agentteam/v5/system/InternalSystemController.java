package com.agentteam.v5.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v5/internal/systems")
public class InternalSystemController {
    private final SystemProfileRepository systems;
    private final ObjectMapper objectMapper;
    private final BusinessModelConfigService businessModels;
    private final AgentConfigurationService agentConfigurations;

    public InternalSystemController(SystemProfileRepository systems, ObjectMapper objectMapper,
                                    BusinessModelConfigService businessModels,
                                    AgentConfigurationService agentConfigurations) {
        this.systems = systems;
        this.objectMapper = objectMapper;
        this.businessModels = businessModels;
        this.agentConfigurations = agentConfigurations;
    }

    @GetMapping("/{systemId}/model-config")
    Map<String, Object> modelConfig(@PathVariable String systemId,
                                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "default") String stage,
                                    @org.springframework.web.bind.annotation.RequestParam(name = "profile_id", defaultValue = "") String profileId) {
        var model = businessModels.resolve(systemId, stage);
        var agentConfig = agentConfigurations.internal(systemId);
        var response = new LinkedHashMap<String, Object>();
        response.put("managed", model.managed());
        response.put("configured", model.configured());
        put(response, "model_id", model.modelId());
        put(response, "name", model.name());
        put(response, "provider", model.preset());
        put(response, "model", model.model());
        put(response, "base_url", model.baseUrl());
        put(response, "api_key", model.apiKey());
        response.put("model_profiles", agentConfig.modelProfiles().stream().map(profile -> Map.of(
                "id", profile.id(),
                "name", profile.name(),
                "provider", profile.provider(),
                "base_url", profile.baseUrl(),
                "api_key", profile.apiKey(),
                "model", profile.model())).toList());
        response.put("agent_roles", agentConfig.agentRoles().stream().map(role -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", role.id());
            item.put("name", role.name());
            item.put("engine", role.engine());
            item.put("model_profile_ref", role.modelProfileRef());
            item.put("path_scope", role.pathScope());
            item.put("prompt", role.prompt());
            if (role.maxTurns() != null) item.put("max_turns", role.maxTurns());
            if (role.timeoutSeconds() != null) item.put("timeout_seconds", role.timeoutSeconds());
            return item;
        }).toList());
        response.put("default_role_id", agentConfig.defaultRoleId());

        if (!model.managed() && profileId.isBlank()) {
            var defaultProfileId = agentConfig.agentRoles().stream()
                    .filter(role -> role.id().equals(agentConfig.defaultRoleId()))
                    .map(AgentConfigurationService.AgentRole::modelProfileRef).findFirst().orElse("");
            agentConfig.modelProfiles().stream().filter(profile -> profile.id().equals(defaultProfileId)).findFirst()
                    .ifPresent(profile -> applyProfile(response, profile));
        }

        // HTTP 执行可按 role 指定 Profile；只传引用，不让 key 经过 Temporal。
        if (!profileId.isBlank()) {
            var selected = agentConfig.modelProfiles().stream()
                    .filter(profile -> profile.id().equals(profileId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("模型 Profile 不存在: " + profileId));
            applyProfile(response, selected);
        }
        return response;
    }

    private void applyProfile(Map<String, Object> response, AgentConfigurationService.ModelProfile selected) {
        response.put("managed", true);
        response.put("configured", !selected.model().isBlank() && !selected.apiKey().isBlank());
        response.put("model_id", selected.id());
        response.put("name", selected.name());
        response.put("provider", selected.provider());
        response.put("model", selected.model());
        response.put("base_url", selected.baseUrl());
        response.put("api_key", selected.apiKey());
    }

    @GetMapping("/{systemId}/claude-model-config")
    Map<String, Object> claudeModelConfig(@PathVariable String systemId) {
        var profile = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var config = readMap(profile.modelProviderConfig());
        var key = businessModels.resolveClaudeKey(systemId);
        var model = config.get("claudeModel");
        var configured = model != null && !String.valueOf(model).isBlank()
                && key.configured();

        // 内部接口按系统解析复用关系，密钥不进入普通系统接口或心跳。
        var response = new LinkedHashMap<String, Object>();
        response.put("managed", key.managed());
        response.put("configured", configured);
        response.put("source", configured ? key.source() : (key.managed() ? "system_invalid" : "unconfigured"));
        put(response, "preset", config.get("claudePreset"));
        put(response, "model", model);
        put(response, "base_url", config.get("claudeBaseUrl"));
        put(response, "business_model_id", key.businessModelId());
        put(response, "api_key", key.apiKey());
        return response;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }
}
