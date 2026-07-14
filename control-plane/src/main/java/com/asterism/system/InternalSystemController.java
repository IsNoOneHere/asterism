package com.asterism.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v5/internal/systems")
public class InternalSystemController {
    private final AgentConfigurationService configurations;

    public InternalSystemController(AgentConfigurationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/{systemId}/model-config")
    Map<String, Object> modelConfig(@PathVariable String systemId,
                                    @RequestParam(defaultValue = "default") String stage,
                                    @RequestParam(name = "profile_id", defaultValue = "") String profileId) {
        var config = configurations.internal(systemId);
        var selectedId = profileId.isBlank() ? config.modelRouting().resolve(stage) : profileId;
        var selected = config.modelProfiles().stream()
                .filter(profile -> profile.id().equals(selectedId))
                .findFirst().orElse(null);
        if (!selectedId.isBlank() && selected == null) {
            throw new IllegalArgumentException("模型 Profile 不存在: " + selectedId);
        }

        // Internal API 是 activity 读取完整配置的唯一入口，密钥不会进入 workflow payload。
        var response = new LinkedHashMap<String, Object>();
        response.put("managed", !config.modelProfiles().isEmpty());
        response.put("configured", selected != null && !selected.model().isBlank() && !selected.apiKey().isBlank());
        if (selected != null) applyProfile(response, selected);
        response.put("model_profiles", config.modelProfiles().stream().map(this::profileMap).toList());
        response.put("agent_roles", config.agentRoles().stream().map(this::roleMap).toList());
        response.put("default_role_id", config.defaultRoleId());
        response.put("execution_mode", config.executionMode());
        return response;
    }

    private Map<String, Object> profileMap(AgentConfigurationService.ModelProfile profile) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", profile.id());
        item.put("name", profile.name());
        item.put("provider", profile.provider());
        item.put("base_url", profile.baseUrl());
        item.put("api_key", profile.apiKey());
        item.put("model", profile.model());
        return item;
    }

    private Map<String, Object> roleMap(AgentConfigurationService.AgentRole role) {
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
    }

    private void applyProfile(Map<String, Object> response, AgentConfigurationService.ModelProfile profile) {
        response.put("model_id", profile.id());
        response.put("name", profile.name());
        response.put("provider", profile.provider());
        response.put("model", profile.model());
        response.put("base_url", profile.baseUrl());
        response.put("api_key", profile.apiKey());
    }
}
