package com.agentteam.v5.system;

import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.identity.SystemMembershipRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v5/systems")
public class SystemController {
    private static final Logger log = LoggerFactory.getLogger(SystemController.class);
    private final SystemProfileRepository systems;
    private final SystemMembershipRepository memberships;
    private final SystemAccessService access;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;

    public SystemController(SystemProfileRepository systems, SystemMembershipRepository memberships,
                            SystemAccessService access, JdbcAggregateTemplate aggregate, ObjectMapper objectMapper) {
        this.systems = systems;
        this.memberships = memberships;
        this.access = access;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    Iterable<SystemProfile> list(Authentication actor) {
        var visible = access.isAdmin(actor) ? systems.findAll() : systems.findAllById(memberships.findSystemIdsForUser(actor.getName()));
        return java.util.stream.StreamSupport.stream(visible.spliterator(), false)
                .map(this::maskProfile)
                .toList();
    }

    @PostMapping
    SystemProfile create(@RequestBody UpsertSystemRequest request, Authentication actor) {
        if (systems.existsById(request.systemId())) {
            throw new IllegalStateException("系统已存在");
        }
        var saved = aggregate.insert(toProfile(request.systemId(), request, null, actor.getName()));
        memberships.upsertMembership(saved.systemId(), actor.getName(), "owner", actor.getName());
        return maskProfile(saved);
    }

    @PutMapping("/{systemId}")
    SystemProfile update(@PathVariable String systemId, @RequestBody UpsertSystemRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        var existing = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var saved = aggregate.update(toProfile(systemId, request, existing, existing.createdBy()));
        return maskProfile(saved);
    }

    @PatchMapping("/{systemId}/profile")
    SystemProfile updateProfile(@PathVariable String systemId, @Valid @RequestBody ProfileRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        var existing = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var saved = aggregate.update(new SystemProfile(
                existing.systemId(), request.name(), request.description(), request.repoPath(), request.ownerUserId(),
                json(request.allowedPaths()), json(request.forbiddenPaths()), json(request.testCommands()),
                existing.agentConfig(), existing.modelProviderConfig(), existing.createdBy(), existing.createdAt(), Instant.now()));
        log.info("系统基础配置已更新 system={} actor={}", systemId, actor.getName());
        return maskProfile(saved);
    }

    @PatchMapping("/{systemId}/execution-config")
    SystemProfile updateExecutionConfig(@PathVariable String systemId, @Valid @RequestBody ExecutionConfigRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        var existing = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var config = new LinkedHashMap<>(readMap(existing.agentConfig()));
        config.put("executionProvider", request.executionProvider());
        config.put("claudeMaxTurns", request.claudeMaxTurns());
        config.put("executionTimeoutSeconds", request.executionTimeoutSeconds());
        var saved = aggregate.update(copy(existing, json(config), existing.modelProviderConfig()));
        log.info("系统执行配置已更新 system={} provider={} actor={}", systemId, request.executionProvider(), actor.getName());
        return maskProfile(saved);
    }

    @GetMapping("/{systemId}/members")
    Iterable<SystemMembershipRepository.SystemMemberView> members(@PathVariable String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        return memberships.listMembers(systemId);
    }

    @DeleteMapping("/{systemId}/members/{userId}/{role}")
    void deleteMember(@PathVariable String systemId, @PathVariable String userId, @PathVariable String role,
                      Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        if ("owner".equals(role) && memberships.countOwners(systemId) <= 1) {
            throw new IllegalStateException("不能移除最后一个 owner");
        }
        memberships.deleteMembership(systemId, userId, role);
    }

    private SystemProfile toProfile(String systemId, UpsertSystemRequest request, SystemProfile existing, String createdBy) {
        var now = Instant.now();
        var modelConfig = mergeSecret(existing == null ? null : existing.modelProviderConfig(), request.modelProviderConfig());
        return new SystemProfile(
                systemId,
                request.name(),
                request.description(),
                request.repoPath(),
                request.ownerUserId(),
                json(request.allowedPaths()),
                json(request.forbiddenPaths()),
                json(request.testCommands()),
                request.agentConfig() == null && existing != null ? existing.agentConfig() : json(request.agentConfig() == null ? Map.of() : request.agentConfig()),
                json(modelConfig),
                createdBy,
                existing == null ? now : existing.createdAt(),
                now);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("系统配置不是合法 JSON", error);
        }
    }

    private SystemProfile maskProfile(SystemProfile profile) {
        return new SystemProfile(
                profile.systemId(),
                profile.name(),
                profile.description(),
                profile.repoPath(),
                profile.ownerUserId(),
                profile.allowedPaths(),
                profile.forbiddenPaths(),
                profile.testCommands(),
                profile.agentConfig(),
                json(maskSecrets(readMap(profile.modelProviderConfig()))),
                profile.createdBy(),
                profile.createdAt(),
                profile.updatedAt());
    }

    private Map<String, Object> mergeSecret(String currentJson, Map<String, Object> requested) {
        var merged = new LinkedHashMap<>(readMap(currentJson));
        if (requested != null) {
            merged.putAll(requested);
        }
        // 前端不填 apiKey 表示保留旧值，避免保存配置时清空密钥。
        if ((requested == null || !requested.containsKey("apiKey")) && readMap(currentJson).containsKey("apiKey")) {
            merged.put("apiKey", readMap(currentJson).get("apiKey"));
        }
        return merged;
    }

    private SystemProfile copy(SystemProfile existing, String agentConfig, String modelProviderConfig) {
        return new SystemProfile(existing.systemId(), existing.name(), existing.description(), existing.repoPath(),
                existing.ownerUserId(), existing.allowedPaths(), existing.forbiddenPaths(), existing.testCommands(),
                agentConfig, modelProviderConfig, existing.createdBy(), existing.createdAt(), Instant.now());
    }

    private Map<String, Object> maskSecrets(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return Map.of();
        }
        var masked = new LinkedHashMap<String, Object>();
        config.forEach((key, value) -> masked.put(key,
                "modelProfiles".equals(key) ? maskModelProfiles(value) : isSecret(key) ? "******" : maskValue(value)));
        return masked;
    }

    private Object maskModelProfiles(Object value) {
        if (!(value instanceof List<?> profiles)) return List.of();
        return profiles.stream().filter(Map.class::isInstance).map(item -> {
            var profile = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) item).forEach((key, fieldValue) -> {
                var name = String.valueOf(key);
                if (!isSecret(name)) profile.put(name, maskValue(fieldValue));
            });
            var raw = (Map<?, ?>) item;
            var key = raw.containsKey("apiKey") ? raw.get("apiKey") : raw.get("api_key");
            profile.put("apiKeySet", key != null && !String.valueOf(key).isBlank());
            return profile;
        }).toList();
    }

    private Object maskValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var normalized = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                var name = String.valueOf(key);
                normalized.put(name, isSecret(name) ? "******" : maskValue(item));
            });
            return normalized;
        }
        if (value instanceof List<?> list) return list.stream().map(this::maskValue).toList();
        return value;
    }

    private boolean isSecret(String key) {
        return "apiKey".equalsIgnoreCase(key) || "claudeApiKey".equalsIgnoreCase(key)
                || "api_key".equalsIgnoreCase(key) || "claude_api_key".equalsIgnoreCase(key);
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

    public record UpsertSystemRequest(
            @NotBlank String systemId,
            @NotBlank String name,
            String description,
            @NotBlank String repoPath,
            @NotBlank String ownerUserId,
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> testCommands,
            Map<String, Object> agentConfig,
            Map<String, Object> modelProviderConfig) {
    }

    public record ProfileRequest(@NotBlank String name, String description, @NotBlank String repoPath,
                                 @NotBlank String ownerUserId, List<String> allowedPaths,
                                 List<String> forbiddenPaths, List<String> testCommands) {
    }

    public record ExecutionConfigRequest(@NotBlank String executionProvider, Integer claudeMaxTurns,
                                         Integer executionTimeoutSeconds) {
    }
}
