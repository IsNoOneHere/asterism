package com.asterism.system;

import com.asterism.common.ApiException;
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
    private static final List<String> ENGINES = List.of("claude_sdk_team", "fake");
    private static final List<String> PROVIDERS = List.of("anthropic", "openai-compat");
    private static final List<String> STRUCTURED_OUTPUTS = List.of("json_schema", "json_object", "prompt_only");
    private static final List<String> BUILTIN_AGENTS = List.of("product", "vision", "developer");
    private static final int DEFAULT_MAX_REVISIONS = 5;

    private final SystemProfileRepository systems;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;
    private final SystemConfigLock lock;
    private final ModelConnectionClient connections;

    public AgentConfigurationService(SystemProfileRepository systems, JdbcAggregateTemplate aggregate,
                                     ObjectMapper objectMapper, SystemConfigLock lock, ModelConnectionClient connections) {
        this.systems = systems;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
        this.lock = lock;
        this.connections = connections;
    }

    public AgentConfigurationResponse get(String systemId) {
        var state = load(systemId);
        return new AgentConfigurationResponse(
                state.profiles().stream().map(ModelProfile::masked).toList(), state.agents(),
                maxRevisions(state.config()), ENGINES,
                readMigration(state.config().get("executionMigration")));
    }

    public InternalAgentConfiguration internal(String systemId) {
        var state = load(systemId);
        return new InternalAgentConfiguration(state.profiles(), state.agents(), maxRevisions(state.config()));
    }

    public ModelConnectionClient.ConnectionResult testProfile(String systemId, String profileId) {
        var profiles = load(systemId).profiles();
        profileIndex(profiles, profileId);
        return connections.test(systemId, profileId);
    }

    public ModelConnectionClient.CapabilityResult testCapability(String systemId, String profileId, String capability) {
        var profiles = load(systemId).profiles();
        profileIndex(profiles, profileId);
        return connections.testCapability(systemId, profileId, capability);
    }

    @Transactional
    public AgentConfigurationResponse createProfile(String systemId, ModelProfileRequest request) {
        var state = locked(systemId);
        requireProvider(request.provider());
        var imageInput = request.imageInputEnabled();
        var profile = new ModelProfile("mp-" + UUID.randomUUID(), value(request.name()), request.provider(),
                value(request.baseUrl()), value(request.apiKey()), value(request.model()), imageInput,
                structuredOutput(request.structuredOutput()), imageInput);
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
        var imageInput = request.imageInputEnabled();
        if (!imageInput && state.agents().stream()
                .anyMatch(agent -> "vision".equals(agent.name()) && profileId.equals(agent.modelProfileRef()))) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_PROFILE_IN_USE",
                    "Vision Agent 正在使用该 Profile，不能关闭图片输入能力");
        }
        state.profiles().set(index, new ModelProfile(profileId, value(request.name()), request.provider(),
                value(request.baseUrl()), apiKey, value(request.model()), imageInput,
                structuredOutput(request.structuredOutput()), imageInput));
        save(state);
        log.info("模型 Profile 已更新 system={} profileId={}", systemId, profileId);
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse deleteProfile(String systemId, String profileId) {
        var state = locked(systemId);
        profileIndex(state.profiles(), profileId);
        if (state.agents().stream().anyMatch(agent -> profileId.equals(agent.modelProfileRef()))) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_PROFILE_IN_USE", "模型 Profile 正在被 Agent 使用");
        }
        state.profiles().removeIf(profile -> profile.id().equals(profileId));
        save(state);
        log.info("模型 Profile 已删除 system={} profileId={}", systemId, profileId);
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse updateAgent(String systemId, String agentName, AgentRequest request) {
        var state = locked(systemId);
        var index = agentIndex(state.agents(), agentName);
        if (!value(request.name()).isBlank() && !agentName.equals(request.name().trim())) {
            throw new IllegalArgumentException("Agent 名称不可修改");
        }
        Agent updated;
        if ("product".equals(agentName)) {
            requireOptionalProfile(state.profiles(), request.modelProfileRef());
            updated = builtinModelAgent(agentName, value(request.modelProfileRef()));
        } else if ("vision".equals(agentName)) {
            var profileId = value(request.modelProfileRef());
            if (profileId.isBlank()) throw new IllegalArgumentException("Vision Agent 必须绑定 Model Profile");
            var profile = state.profiles().get(profileIndex(state.profiles(), profileId));
            if (!profile.imageInputEnabled()) throw new IllegalArgumentException("Vision Agent 只能绑定支持图片输入的 Profile");
            updated = builtinModelAgent(agentName, profileId);
        } else if ("developer".equals(agentName)) {
            updated = executionAgent(request, state.profiles());
        } else {
            throw new IllegalArgumentException("只支持内置 Agent: " + agentName);
        }
        state.agents().set(index, updated);
        save(state);
        log.info("Agent 已更新 system={} agent={} engine={}", systemId, agentName, updated.engine());
        return get(systemId);
    }

    @Transactional
    public AgentConfigurationResponse updateExecutionSettings(String systemId, ExecutionSettingsRequest request) {
        var state = locked(systemId);
        var maxRevisions = request.maxRevisions() == null ? DEFAULT_MAX_REVISIONS : request.maxRevisions();
        if (maxRevisions < 1 || maxRevisions > 20) throw new IllegalArgumentException("最大修订轮次必须在 1 到 20 之间");
        state.config().put("maxRevisions", maxRevisions);
        save(state);
        log.info("执行策略已更新 system={} maxRevisions={}", systemId, maxRevisions);
        return get(systemId);
    }

    private State locked(String systemId) {
        lock.lockAgentConfiguration(systemId);
        return load(systemId);
    }

    private State load(String systemId) {
        var profile = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var config = new LinkedHashMap<>(readMap(profile.modelProviderConfig()));
        var profiles = readProfiles(config.get("modelProfiles"));
        var agents = readAgents(config.get("agents"));
        ensureBuiltinAgents(agents, profiles);
        return new State(profile, config, profiles, agents);
    }

    private void save(State state) {
        state.config().put("modelProfiles", state.profiles());
        state.config().put("agents", state.agents());
        var current = state.profile();
        aggregate.update(new SystemProfile(current.systemId(), current.name(), current.description(), current.repoPath(),
                current.ownerUserId(), current.allowedPaths(), current.forbiddenPaths(), current.testCommands(),
                "{}", json(state.config()), current.createdBy(), current.createdAt(), Instant.now()));
    }

    private List<ModelProfile> readProfiles(Object value) {
        var profiles = convertList(value, ModelProfile.class);
        for (var index = 0; index < profiles.size(); index++) {
            var profile = profiles.get(index);
            var imageInput = profile.imageInputEnabled();
            profiles.set(index, new ModelProfile(profile.id(), profile.name(), profile.provider(), profile.baseUrl(),
                    profile.apiKey(), profile.model(), imageInput,
                    structuredOutput(profile.structuredOutput()), imageInput));
        }
        return profiles;
    }

    private List<Agent> readAgents(Object value) {
        var agents = convertList(value, Agent.class).stream()
                .filter(agent -> BUILTIN_AGENTS.contains(value(agent.name())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (var index = 0; index < agents.size(); index++) agents.set(index, normalized(agents.get(index)));
        return agents;
    }

    private <T> List<T> convertList(Object value, Class<T> type) {
        var result = new ArrayList<T>();
        if (value instanceof List<?> list) {
            for (var item : list) result.add(objectMapper.convertValue(item, type));
        }
        return result;
    }

    private void ensureBuiltinAgents(List<Agent> agents, List<ModelProfile> profiles) {
        if (findAgentIndex(agents, "product") < 0) agents.add(builtinModelAgent("product", ""));
        if (findAgentIndex(agents, "vision") < 0) {
            // 旧配置迁移优先复用 Product 的图片模型，迁移后运行时只读取显式绑定。
            var productRef = agents.stream().filter(agent -> "product".equals(agent.name()))
                    .map(Agent::modelProfileRef).findFirst().orElse("");
            var visionRef = profiles.stream()
                    .filter(profile -> profile.id().equals(productRef) && profile.imageInputEnabled())
                    .map(ModelProfile::id).findFirst()
                    .orElseGet(() -> profiles.stream().filter(ModelProfile::imageInputEnabled)
                            .map(ModelProfile::id).findFirst().orElse(""));
            agents.add(builtinModelAgent("vision", visionRef));
        }
        if (findAgentIndex(agents, "developer") < 0) {
            agents.add(new Agent("developer", "builtin", "claude_sdk_team", "", List.of(), "", 50, 600));
        }
        // 内置 Agent 固定顺序，配置页面与 Worker 共用同一份语义。
        agents.sort((left, right) -> Integer.compare(BUILTIN_AGENTS.indexOf(left.name()), BUILTIN_AGENTS.indexOf(right.name())));
    }

    private Agent normalized(Agent agent) {
        var name = value(agent.name());
        if ("product".equals(name) || "vision".equals(name)) {
            return builtinModelAgent(name, value(agent.modelProfileRef()));
        }
        var engine = ENGINES.contains(value(agent.engine())) ? value(agent.engine()) : "claude_sdk_team";
        return new Agent("developer", "builtin", engine, value(agent.modelProfileRef()),
                agent.pathScope() == null ? List.of() : agent.pathScope(), value(agent.prompt()),
                agent.maxTurns(), agent.timeoutSeconds());
    }

    private Agent builtinModelAgent(String name, String profileId) {
        return new Agent(name, "builtin", "", profileId, List.of(), "", null, null);
    }

    private Agent executionAgent(AgentRequest request, List<ModelProfile> profiles) {
        if (!ENGINES.contains(request.engine())) throw new IllegalArgumentException("不支持的执行内核: " + request.engine());
        requireOptionalProfile(profiles, request.modelProfileRef());
        return new Agent("developer", "builtin", request.engine(), value(request.modelProfileRef()),
                request.pathScope() == null ? List.of() : request.pathScope(), value(request.prompt()),
                request.maxTurns(), request.timeoutSeconds());
    }

    private ConfigurationMigration readMigration(Object value) {
        if (!(value instanceof Map<?, ?> migration)) return new ConfigurationMigration(false, List.of());
        var from = migration.get("from") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.<String>of();
        return new ConfigurationMigration(Boolean.TRUE.equals(migration.get("migrated")), from);
    }

    private int maxRevisions(Map<String, Object> config) {
        var value = config.get("maxRevisions");
        return value instanceof Number number && number.intValue() > 0
                ? number.intValue() : DEFAULT_MAX_REVISIONS;
    }

    private void requireProvider(String provider) {
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("不支持的模型 provider: " + provider);
    }

    private String structuredOutput(String mode) {
        var normalized = value(mode);
        return STRUCTURED_OUTPUTS.contains(normalized) ? normalized : "json_object";
    }

    private void requireOptionalProfile(List<ModelProfile> profiles, String profileId) {
        if (!value(profileId).isBlank()) profileIndex(profiles, profileId);
    }

    private int profileIndex(List<ModelProfile> profiles, String id) {
        for (var index = 0; index < profiles.size(); index++) if (profiles.get(index).id().equals(id)) return index;
        throw new IllegalArgumentException("模型 Profile 不存在: " + id);
    }

    private int agentIndex(List<Agent> agents, String name) {
        var index = findAgentIndex(agents, name);
        if (index >= 0) return index;
        throw new IllegalArgumentException("Agent 不存在: " + name);
    }

    private int findAgentIndex(List<Agent> agents, String name) {
        for (var index = 0; index < agents.size(); index++) if (agents.get(index).name().equals(name)) return index;
        return -1;
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

    public record ModelProfile(String id, String name, String provider, String baseUrl, String apiKey, String model,
                               Boolean imageInput, String structuredOutput, Boolean supportsVision) {
        public ModelProfile(String id, String name, String provider, String baseUrl, String apiKey, String model,
                            boolean supportsVision) {
            this(id, name, provider, baseUrl, apiKey, model, null, "json_object", supportsVision);
        }

        public boolean imageInputEnabled() {
            return imageInput != null ? imageInput : Boolean.TRUE.equals(supportsVision);
        }

        ModelProfileView masked() {
            return new ModelProfileView(id, name, provider, baseUrl, model, apiKey != null && !apiKey.isBlank(),
                    imageInputEnabled(), imageInputEnabled(), structuredOutput);
        }
    }
    public record ModelProfileView(String id, String name, String provider, String baseUrl, String model,
                                   boolean apiKeySet, boolean supportsVision, boolean imageInput,
                                   String structuredOutput) {}
    public record Agent(String name, String kind, String engine, String modelProfileRef, List<String> pathScope,
                        String prompt, Integer maxTurns, Integer timeoutSeconds) {}
    public record ModelProfileRequest(String name, String provider, String baseUrl, String apiKey, String model,
                                      Boolean supportsVision, Boolean imageInput, String structuredOutput) {
        public ModelProfileRequest(String name, String provider, String baseUrl, String apiKey, String model,
                                   boolean supportsVision) {
            this(name, provider, baseUrl, apiKey, model, supportsVision, null, "json_object");
        }

        boolean imageInputEnabled() {
            return imageInput != null ? imageInput : Boolean.TRUE.equals(supportsVision);
        }
    }
    public record AgentRequest(String name, String engine, String modelProfileRef, List<String> pathScope,
                               String prompt, Integer maxTurns, Integer timeoutSeconds) {}
    public record ExecutionSettingsRequest(Integer maxRevisions) {}
    public record AgentConfigurationResponse(List<ModelProfileView> modelProfiles, List<Agent> agents,
                                             int maxRevisions, List<String> engines, ConfigurationMigration migration) {}
    public record ConfigurationMigration(boolean migrated, List<String> from) {}
    public record InternalAgentConfiguration(List<ModelProfile> modelProfiles, List<Agent> agents,
                                             int maxRevisions) {}

    private record State(SystemProfile profile, LinkedHashMap<String, Object> config,
                         List<ModelProfile> profiles, List<Agent> agents) {}
}
