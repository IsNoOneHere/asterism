package com.agentteam.v5.system;

import com.agentteam.v5.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class AgentConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(AgentConfigurationService.class);
    private static final List<String> ENGINES = List.of("fake", "http", "claude_sdk", "deepagents");
    private static final List<String> PROVIDERS = List.of("anthropic", "openai-compat");

    private final SystemProfileRepository systems;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;
    private final SystemConfigLock lock;

    public AgentConfigurationService(SystemProfileRepository systems, JdbcAggregateTemplate aggregate,
                                     ObjectMapper objectMapper, SystemConfigLock lock) {
        this.systems = systems;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
        this.lock = lock;
    }

    public AgentConfigurationResponse get(String systemId) {
        var state = load(systemId);
        return new AgentConfigurationResponse(
                state.profiles().stream().map(ModelProfile::masked).toList(),
                state.roles(), state.defaultRoleId(), ENGINES);
    }

    public InternalAgentConfiguration internal(String systemId) {
        var state = load(systemId);
        return new InternalAgentConfiguration(state.profiles(), state.roles(), state.defaultRoleId());
    }

    @Transactional
    public AgentConfigurationResponse createProfile(String systemId, ModelProfileRequest request) {
        var state = locked(systemId);
        requireProvider(request.provider());
        var profile = new ModelProfile("mp-" + UUID.randomUUID(), value(request.name()), request.provider(),
                value(request.baseUrl()), value(request.apiKey()), value(request.model()));
        state.profiles().add(profile);
        save(state);
        log.info("模型 Profile 已新增 system={} profileId={}", systemId, profile.id());
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse updateProfile(String systemId, String profileId, ModelProfileRequest request) {
        var state = locked(systemId);
        requireProvider(request.provider());
        var index = profileIndex(state.profiles(), profileId);
        var current = state.profiles().get(index);
        var apiKey = value(request.apiKey()).isBlank() ? current.apiKey() : request.apiKey();
        state.profiles().set(index, new ModelProfile(profileId, value(request.name()), request.provider(),
                value(request.baseUrl()), apiKey, value(request.model())));
        save(state);
        log.info("模型 Profile 已更新 system={} profileId={}", systemId, profileId);
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse deleteProfile(String systemId, String profileId) {
        var state = locked(systemId);
        profileIndex(state.profiles(), profileId);
        if (state.roles().stream().anyMatch(role -> profileId.equals(role.modelProfileRef()))) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_PROFILE_IN_USE", "模型 Profile 正在被 Agent 角色使用");
        }
        state.profiles().removeIf(profile -> profile.id().equals(profileId));
        save(state);
        log.info("模型 Profile 已删除 system={} profileId={}", systemId, profileId);
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse createRole(String systemId, AgentRoleRequest request) {
        var state = locked(systemId);
        validateRole(state, request);
        var role = role("role-" + UUID.randomUUID(), request);
        state.roles().add(role);
        if (state.defaultRoleId().isBlank()) state.setDefaultRoleId(role.id());
        save(state);
        log.info("Agent 角色已新增 system={} roleId={} engine={}", systemId, role.id(), role.engine());
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse updateRole(String systemId, String roleId, AgentRoleRequest request) {
        var state = locked(systemId);
        validateRole(state, request);
        var index = roleIndex(state.roles(), roleId);
        state.roles().set(index, role(roleId, request));
        save(state);
        log.info("Agent 角色已更新 system={} roleId={} engine={}", systemId, roleId, request.engine());
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse deleteRole(String systemId, String roleId) {
        var state = locked(systemId);
        roleIndex(state.roles(), roleId);
        state.roles().removeIf(role -> role.id().equals(roleId));
        if (roleId.equals(state.defaultRoleId())) {
            state.setDefaultRoleId(state.roles().isEmpty() ? "" : state.roles().getFirst().id());
        }
        save(state);
        log.info("Agent 角色已删除 system={} roleId={}", systemId, roleId);
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse updateDefaultRole(String systemId, DefaultRoleRequest request) {
        var state = locked(systemId);
        if (!value(request.roleId()).isBlank()) roleIndex(state.roles(), request.roleId());
        state.setDefaultRoleId(value(request.roleId()));
        save(state);
        log.info("默认 Agent 角色已更新 system={} roleId={}", systemId, state.defaultRoleId());
        return get(systemId);
    }

    private State locked(String systemId) {
        lock.lockBusinessModels(systemId);
        return load(systemId);
    }

    private State load(String systemId) {
        var profile = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var config = new LinkedHashMap<>(readMap(profile.modelProviderConfig()));
        return new State(profile, config, readProfiles(config.get("modelProfiles")),
                readRoles(config.get("agentRoles")), value(config.get("defaultAgentRoleId")));
    }

    private void save(State state) {
        state.config().put("modelProfiles", state.profiles());
        state.config().put("agentRoles", state.roles());
        state.config().put("defaultAgentRoleId", state.defaultRoleId());
        var current = state.profile();
        aggregate.update(new SystemProfile(current.systemId(), current.name(), current.description(), current.repoPath(),
                current.ownerUserId(), current.allowedPaths(), current.forbiddenPaths(), current.testCommands(),
                current.agentConfig(), json(state.config()), current.createdBy(), current.createdAt(), Instant.now()));
    }

    private List<ModelProfile> readProfiles(Object value) {
        return convertList(value, ModelProfile.class);
    }

    private List<AgentRole> readRoles(Object value) {
        return convertList(value, AgentRole.class);
    }

    private <T> List<T> convertList(Object value, Class<T> type) {
        var result = new ArrayList<T>();
        if (value instanceof List<?> list) {
            for (var item : list) result.add(objectMapper.convertValue(item, type));
        }
        return result;
    }

    private void validateRole(State state, AgentRoleRequest request) {
        if (!ENGINES.contains(request.engine())) throw new IllegalArgumentException("不支持的执行内核: " + request.engine());
        var profileId = value(request.modelProfileRef());
        if (!"fake".equals(request.engine()) && profileId.isBlank()) {
            throw new IllegalArgumentException("真实执行内核必须选择模型 Profile");
        }
        if (!profileId.isBlank()) profileIndex(state.profiles(), profileId);
    }

    private void requireProvider(String provider) {
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("不支持的模型 provider: " + provider);
    }

    private AgentRole role(String id, AgentRoleRequest request) {
        return new AgentRole(id, value(request.name()), request.engine(), value(request.modelProfileRef()),
                request.pathScope() == null ? List.of() : request.pathScope(), value(request.prompt()),
                request.maxTurns(), request.timeoutSeconds());
    }

    private int profileIndex(List<ModelProfile> profiles, String id) {
        for (var index = 0; index < profiles.size(); index++) if (profiles.get(index).id().equals(id)) return index;
        throw new IllegalArgumentException("模型 Profile 不存在: " + id);
    }

    private int roleIndex(List<AgentRole> roles, String id) {
        for (var index = 0; index < roles.size(); index++) if (roles.get(index).id().equals(id)) return index;
        throw new IllegalArgumentException("Agent 角色不存在: " + id);
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
            throw new IllegalArgumentException("Agent 配置不是合法 JSON", error);
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ModelProfile(String id, String name, String provider, String baseUrl, String apiKey, String model) {
        ModelProfileView masked() {
            return new ModelProfileView(id, name, provider, baseUrl, model, apiKey != null && !apiKey.isBlank());
        }
    }
    public record ModelProfileView(String id, String name, String provider, String baseUrl, String model,
                                   boolean apiKeySet) {}
    public record AgentRole(String id, String name, String engine, String modelProfileRef, List<String> pathScope,
                            String prompt, Integer maxTurns, Integer timeoutSeconds) {}
    public record ModelProfileRequest(String name, String provider, String baseUrl, String apiKey, String model) {}
    public record AgentRoleRequest(String name, String engine, String modelProfileRef, List<String> pathScope,
                                   String prompt, Integer maxTurns, Integer timeoutSeconds) {}
    public record DefaultRoleRequest(String roleId) {}
    public record AgentConfigurationResponse(List<ModelProfileView> modelProfiles, List<AgentRole> agentRoles,
                                             String defaultRoleId, List<String> engines) {}
    public record InternalAgentConfiguration(List<ModelProfile> modelProfiles, List<AgentRole> agentRoles,
                                             String defaultRoleId) {}

    private static final class State {
        private final SystemProfile profile;
        private final LinkedHashMap<String, Object> config;
        private final List<ModelProfile> profiles;
        private final List<AgentRole> roles;
        private String defaultRoleId;

        State(SystemProfile profile, LinkedHashMap<String, Object> config, List<ModelProfile> profiles,
              List<AgentRole> roles, String defaultRoleId) {
            this.profile = profile;
            this.config = config;
            this.profiles = profiles;
            this.roles = roles;
            this.defaultRoleId = defaultRoleId;
        }
        SystemProfile profile() { return profile; }
        LinkedHashMap<String, Object> config() { return config; }
        List<ModelProfile> profiles() { return profiles; }
        List<AgentRole> roles() { return roles; }
        String defaultRoleId() { return defaultRoleId; }
        void setDefaultRoleId(String value) { defaultRoleId = value; }
    }
}
