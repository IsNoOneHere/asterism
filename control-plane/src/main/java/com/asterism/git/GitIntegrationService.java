package com.asterism.git;

import com.asterism.system.SystemConfigLock;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class GitIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(GitIntegrationService.class);
    private static final Set<String> KINDS = Set.of("frontend", "backend", "other");
    private static final Set<String> CLONE_MODES = Set.of("local", "gitlab");
    private final SystemGitConfigRepository configs;
    private final SystemProfileRepository systems;
    private final JdbcAggregateTemplate aggregate;
    private final ObjectMapper objectMapper;
    private final SystemConfigLock lock;
    private final GitLabProperties defaults;
    private final GitLabClient gitLab;

    public GitIntegrationService(SystemGitConfigRepository configs, SystemProfileRepository systems,
                                 JdbcAggregateTemplate aggregate, ObjectMapper objectMapper, SystemConfigLock lock,
                                 GitLabProperties defaults, GitLabClient gitLab) {
        this.configs = configs;
        this.systems = systems;
        this.aggregate = aggregate;
        this.objectMapper = objectMapper;
        this.lock = lock;
        this.defaults = defaults;
        this.gitLab = gitLab;
    }

    public PublicGitConfiguration get(String systemId) {
        var state = load(systemId);
        var effective = connection(state);
        return new PublicGitConfiguration(state.repos(), state.releaseMode(), state.validationMode(),
                state.mrTargetBranch(), state.mrLabels(), state.gitlabBaseUrl(), effective.baseUrl(),
                !effective.token().isBlank(), state.gitlabToken().isBlank() && !defaults.safeToken().isBlank());
    }

    public InternalGitConfiguration internal(String systemId) {
        var state = load(systemId);
        var connection = connection(state);
        return new InternalGitConfiguration(state.repos(), state.releaseMode(), state.validationMode(),
                state.mrTargetBranch(), state.mrLabels(), connection.baseUrl(), connection.token());
    }

    @Transactional
    public PublicGitConfiguration initialize(String systemId, UpdateGitConfiguration request) {
        if (configs.existsById(systemId)) return get(systemId);
        return save(systemId, request == null ? legacyRequest(systems.findById(systemId).orElseThrow()) : request);
    }

    @Transactional
    public PublicGitConfiguration update(String systemId, UpdateGitConfiguration request) {
        lock.lockGitConfiguration(systemId);
        systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        return save(systemId, request);
    }

    public GitReadiness readiness(String systemId) {
        var config = internal(systemId);
        if (!"gitlab".equals(config.releaseMode())) return new GitReadiness(true, List.of());
        if (config.baseUrl().isBlank() || config.token().isBlank()) {
            return new GitReadiness(false, config.repos().stream().map(RepoConfig::gitlabProject).toList());
        }
        var unavailable = config.repos().stream()
                .map(RepoConfig::gitlabProject)
                .filter(project -> !gitLab.projectAccessible(config.baseUrl(), config.token(), project))
                .toList();
        return new GitReadiness(unavailable.isEmpty(), unavailable);
    }

    private PublicGitConfiguration save(String systemId, UpdateGitConfiguration request) {
        var existing = configs.findById(systemId).orElse(null);
        var normalized = normalize(request);
        var token = text(request.gitlabToken()).isBlank() && existing != null
                ? existing.gitlabToken() : text(request.gitlabToken());
        var record = new SystemGitConfig(systemId, json(normalized.repos()), normalized.releaseMode(),
                normalized.validationMode(), normalized.mrTargetBranch(), json(normalized.mrLabels()),
                normalized.gitlabBaseUrl(), token, Instant.now());
        if (existing == null) aggregate.insert(record); else aggregate.update(record);
        log.info("Git 与发布配置已更新 system={} repos={} releaseMode={}",
                systemId, normalized.repos().size(), normalized.releaseMode());
        return get(systemId);
    }

    private UpdateGitConfiguration normalize(UpdateGitConfiguration request) {
        if (request == null || request.repos() == null || request.repos().isEmpty()) {
            throw new IllegalArgumentException("至少配置一个代码仓库");
        }
        var releaseMode = text(request.releaseMode()).isBlank() ? "local" : request.releaseMode();
        var validationMode = text(request.validationMode()).isBlank() ? "auto" : request.validationMode();
        if (!Set.of("local", "gitlab").contains(releaseMode)) throw new IllegalArgumentException("不支持的 releaseMode");
        if (!Set.of("auto", "skip").contains(validationMode)) throw new IllegalArgumentException("不支持的 validationMode");
        var ids = new HashSet<String>();
        var repos = request.repos().stream().map(repo -> normalizedRepo(repo, releaseMode, ids)).toList();
        var target = text(request.mrTargetBranch()).trim();
        return new UpdateGitConfiguration(repos, releaseMode, validationMode, target,
                request.mrLabels() == null ? List.of() : request.mrLabels(), text(request.gitlabBaseUrl()),
                text(request.gitlabToken()));
    }

    private RepoConfig normalizedRepo(RepoConfig repo, String releaseMode, Set<String> ids) {
        var id = text(repo.repoId()).trim();
        if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("仓库编号不能为空且不可重复");
        var kind = text(repo.kind()).isBlank() ? "other" : repo.kind();
        var cloneMode = text(repo.cloneMode()).isBlank() ? "local" : repo.cloneMode();
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("不支持的仓库类型: " + kind);
        if (!CLONE_MODES.contains(cloneMode)) throw new IllegalArgumentException("不支持的 cloneMode: " + cloneMode);
        // local 发布会直接修改 localPath，不能使用只准备临时克隆的 GitLab 模式。
        if ("local".equals(releaseMode) && "gitlab".equals(cloneMode)) {
            throw new IllegalArgumentException("local 发布模式只能使用 local 克隆方式");
        }
        if ("local".equals(cloneMode) && text(repo.localPath()).isBlank()) {
            throw new IllegalArgumentException("local 仓库必须配置 localPath");
        }
        if (("gitlab".equals(cloneMode) || "gitlab".equals(releaseMode)) && text(repo.gitlabProject()).isBlank()) {
            throw new IllegalArgumentException("GitLab 仓库必须配置 gitlabProject");
        }
        return new RepoConfig(id, text(repo.name()).isBlank() ? id : repo.name(), kind,
                text(repo.gitlabProject()), text(repo.defaultBranch()).isBlank() ? "main" : repo.defaultBranch(),
                cloneMode, text(repo.localPath()), list(repo.allowedPaths()), list(repo.forbiddenPaths()),
                list(repo.testCommands()));
    }

    private StoredConfiguration load(String systemId) {
        var system = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        return configs.findById(systemId).map(this::stored).orElseGet(() -> stored(legacyRequest(system)));
    }

    private StoredConfiguration stored(SystemGitConfig record) {
        return new StoredConfiguration(readRepos(record.reposJson()), record.releaseMode(), record.validationMode(),
                record.mrTargetBranch(), readList(record.mrLabels()), record.gitlabBaseUrl(), record.gitlabToken());
    }

    private StoredConfiguration stored(UpdateGitConfiguration request) {
        return new StoredConfiguration(request.repos(), request.releaseMode(), request.validationMode(),
                request.mrTargetBranch(), request.mrLabels(), request.gitlabBaseUrl(), request.gitlabToken());
    }

    private UpdateGitConfiguration legacyRequest(SystemProfile system) {
        return new UpdateGitConfiguration(List.of(new RepoConfig("main", system.name(), "other", "", "main",
                "local", system.repoPath(), readList(system.allowedPaths()), readList(system.forbiddenPaths()),
                readList(system.testCommands()))), "local", "auto", "main", List.of(), "", "");
    }

    private Connection connection(StoredConfiguration state) {
        return new Connection(state.gitlabBaseUrl().isBlank() ? defaults.safeBaseUrl() : state.gitlabBaseUrl(),
                state.gitlabToken().isBlank() ? defaults.safeToken() : state.gitlabToken());
    }

    private List<RepoConfig> readRepos(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("仓库配置不是合法 JSON", error);
        }
    }

    private List<String> readList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Git 配置不是合法 JSON", error);
        }
    }

    private List<String> list(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record RepoConfig(String repoId, String name, String kind, String gitlabProject, String defaultBranch,
                             String cloneMode, String localPath, List<String> allowedPaths,
                             List<String> forbiddenPaths, List<String> testCommands) {
    }

    public record UpdateGitConfiguration(List<RepoConfig> repos, String releaseMode, String validationMode,
                                         String mrTargetBranch, List<String> mrLabels, String gitlabBaseUrl,
                                         String gitlabToken) {
    }

    public record PublicGitConfiguration(List<RepoConfig> repos, String releaseMode, String validationMode,
                                         String mrTargetBranch, List<String> mrLabels, String gitlabBaseUrl,
                                         String effectiveGitlabBaseUrl, boolean tokenSet, boolean usingGlobalToken) {
    }

    public record InternalGitConfiguration(List<RepoConfig> repos, String releaseMode, String validationMode,
                                           String mrTargetBranch, List<String> mrLabels, String baseUrl, String token) {
    }

    public record GitReadiness(boolean ready, List<String> unavailableProjects) {
    }

    private record StoredConfiguration(List<RepoConfig> repos, String releaseMode, String validationMode,
                                       String mrTargetBranch, List<String> mrLabels, String gitlabBaseUrl,
                                       String gitlabToken) {
    }

    private record Connection(String baseUrl, String token) {
    }
}
