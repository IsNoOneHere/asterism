package com.asterism.system;

import com.asterism.git.GitIntegrationService;
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
    private final GitIntegrationService git;

    public InternalSystemController(AgentConfigurationService configurations, GitIntegrationService git) {
        this.configurations = configurations;
        this.git = git;
    }

    @GetMapping("/{systemId}/model-config")
    Map<String, Object> modelConfig(@PathVariable String systemId,
                                    @RequestParam(defaultValue = "developer") String agent,
                                    @RequestParam(name = "profile_id", defaultValue = "") String profileId) {
        var config = configurations.internal(systemId);
        String selectedId = profileId;
        if (selectedId.isBlank()) {
            selectedId = config.agents().stream()
                    .filter(item -> item.name().equals(agent))
                    .map(AgentConfigurationService.Agent::modelProfileRef)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agent));
        }
        var resolvedProfileId = selectedId;
        var selected = config.modelProfiles().stream()
                .filter(profile -> profile.id().equals(resolvedProfileId))
                .findFirst().orElse(null);
        if (!resolvedProfileId.isBlank() && selected == null) {
            throw new IllegalArgumentException("模型 Profile 不存在: " + resolvedProfileId);
        }

        // Internal API 是 activity 读取完整配置的唯一入口，密钥不会进入 workflow payload。
        var response = new LinkedHashMap<String, Object>();
        response.put("managed", selected != null);
        response.put("configured", selected != null && !selected.model().isBlank() && !selected.apiKey().isBlank());
        if (selected != null) applyProfile(response, selected);
        response.put("model_profiles", config.modelProfiles().stream().map(this::profileMap).toList());
        response.put("agents", config.agents().stream().map(this::agentMap).toList());
        return response;
    }

    @GetMapping("/{systemId}/git-config")
    Map<String, Object> gitConfig(@PathVariable String systemId) {
        var config = git.internal(systemId);
        var response = new LinkedHashMap<String, Object>();
        response.put("repos", config.repos().stream().map(repo -> Map.of(
                "repo_id", repo.repoId(),
                "name", repo.name(),
                "kind", repo.kind(),
                "gitlab_project", repo.gitlabProject(),
                "default_branch", repo.defaultBranch(),
                "clone_mode", repo.cloneMode(),
                "local_path", repo.localPath(),
                "allowed_paths", repo.allowedPaths(),
                "forbidden_paths", repo.forbiddenPaths(),
                "test_commands", repo.testCommands())).toList());
        response.put("release_mode", config.releaseMode());
        response.put("validation_mode", config.validationMode());
        response.put("mr_target_branch", config.mrTargetBranch());
        response.put("mr_labels", config.mrLabels());
        response.put("base_url", config.baseUrl());
        response.put("token", config.token());
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
        item.put("image_input", profile.imageInputEnabled());
        item.put("structured_output", profile.structuredOutput());
        item.put("supports_vision", profile.imageInputEnabled());
        return item;
    }

    private Map<String, Object> agentMap(AgentConfigurationService.Agent agent) {
        var item = new LinkedHashMap<String, Object>();
        item.put("name", agent.name());
        item.put("kind", agent.kind());
        item.put("engine", agent.engine());
        item.put("model_profile_ref", agent.modelProfileRef());
        item.put("path_scope", agent.pathScope());
        item.put("prompt", agent.prompt());
        if (agent.maxTurns() != null) item.put("max_turns", agent.maxTurns());
        if (agent.timeoutSeconds() != null) item.put("timeout_seconds", agent.timeoutSeconds());
        return item;
    }

    private void applyProfile(Map<String, Object> response, AgentConfigurationService.ModelProfile profile) {
        response.put("model_id", profile.id());
        response.put("name", profile.name());
        response.put("provider", profile.provider());
        response.put("model", profile.model());
        response.put("base_url", profile.baseUrl());
        response.put("api_key", profile.apiKey());
        response.put("image_input", profile.imageInputEnabled());
        response.put("structured_output", profile.structuredOutput());
        response.put("supports_vision", profile.imageInputEnabled());
    }
}
