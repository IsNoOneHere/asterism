package com.asterism.system;

import com.asterism.git.GitIntegrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutionReadinessService {
    private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(90);

    private final Map<String, WorkerReadinessReport> workers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final GitIntegrationService git;

    @Autowired
    public ExecutionReadinessService(ObjectMapper objectMapper, GitIntegrationService git) {
        this.objectMapper = objectMapper;
        this.git = git;
    }

    ExecutionReadinessService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.git = null;
    }

    public void report(WorkerReadinessReport report) {
        workers.put(report.workerId(), report.withReceivedAt(Instant.now()));
    }

    public SystemReadiness readiness(SystemProfile profile) {
        var now = Instant.now();
        var live = workers.values().stream()
                .filter(report -> report.receivedAt() != null && Duration.between(report.receivedAt(), now).compareTo(HEARTBEAT_TTL) <= 0)
                .toList();
        var executionProvider = developerEngine(readMap(profile.modelProviderConfig()));
        var gitConfig = git == null ? null : git.get(profile.systemId());
        var gitlabMode = gitConfig != null && "gitlab".equals(gitConfig.releaseMode());
        var gitReadiness = gitlabMode ? git.readiness(profile.systemId()) : null;
        var targetReports = live.stream()
                .flatMap(worker -> worker.targets().stream().map(target -> new WorkerTarget(worker, target)))
                .filter(item -> profile.systemId().equals(item.target().systemId()))
                .toList();

        var issues = new ArrayList<ReadinessIssue>();
        var prdReady = targetReports.stream().anyMatch(item -> item.target().prdModelReady());
        var planningReady = targetReports.stream().anyMatch(item -> item.target().planningModelReady());
        var diffReady = targetReports.stream().anyMatch(item -> item.target().diffModelReady());
        var repositoryReady = gitlabMode ? gitReadiness.ready()
                : targetReports.stream().anyMatch(item -> item.target().repositoryAccessible() && item.target().gitRepository());
        var workerReady = targetReports.stream().anyMatch(item -> switch (executionProvider) {
            case "http" -> item.worker().capabilities().contains("http") && item.worker().httpProviderReachable()
                    && item.target().diffModelReady();
            case "claude_sdk" -> item.worker().capabilities().contains("claude_sdk") && item.target().claudeReady();
            case "deepagents" -> item.worker().capabilities().contains("deepagents") && item.target().deepagentsReady();
            case "fake" -> item.worker().capabilities().contains("fake");
            default -> false;
        });
        var validationReady = gitConfig == null ? !readList(profile.testCommands()).isEmpty()
                : "skip".equals(gitConfig.validationMode())
                || gitConfig.repos().stream().allMatch(repo -> repo.testCommands() != null && !repo.testCommands().isEmpty());
        var claudeFallback = "claude_sdk".equals(executionProvider) && targetReports.stream()
                .anyMatch(item -> "worker_env".equals(item.target().claudeConfigSource()));

        if (!prdReady) error(issues, "PRD_MODEL_NOT_READY", "需求沟通模型配置不可用");
        if (!planningReady) error(issues, "PLANNING_MODEL_NOT_READY", "方案规划模型配置不可用");
        if ("http".equals(executionProvider) && !diffReady) error(issues, "DIFF_MODEL_NOT_READY", "单次 Diff 模型配置不可用");
        if (executionProvider.isBlank()) error(issues, "EXECUTION_PROVIDER_REQUIRED", "必须显式选择代码执行内核");
        if ("fake".equals(executionProvider)) error(issues, "FAKE_EXECUTION_FORBIDDEN", "模拟执行不能用于业务工作项");
        if (live.isEmpty()) error(issues, "WORKER_OFFLINE", "没有在线 Worker");
        else if (!executionProvider.isBlank() && !"fake".equals(executionProvider) && !workerReady) {
            error(issues, "EXECUTION_CAPABILITY_UNAVAILABLE", "在线 Worker 不具备所选执行能力");
        }
        if (gitlabMode && (gitConfig.effectiveGitlabBaseUrl().isBlank() || !gitConfig.tokenSet())) {
            error(issues, "GITLAB_CONNECTION_NOT_READY", "GitLab 地址或访问 token 未配置");
        } else if (gitlabMode && !gitReadiness.ready()) {
            error(issues, "GITLAB_PROJECT_NOT_READY", "GitLab 项目不可访问: "
                    + String.join(", ", gitReadiness.unavailableProjects()));
        } else if (!repositoryReady) {
            error(issues, "REPOSITORY_NOT_READY", "Worker 无法访问有效 Git 仓库");
        }
        if (!validationReady) error(issues, "TEST_COMMAND_REQUIRED", "至少配置一条测试命令");
        var unrestricted = gitConfig == null ? readList(profile.allowedPaths()).isEmpty()
                : gitConfig.repos().stream().anyMatch(repo -> repo.allowedPaths() == null || repo.allowedPaths().isEmpty());
        if (unrestricted) {
            issues.add(new ReadinessIssue("ALLOWED_PATHS_EMPTY", "warning", "修改范围未限制"));
        }
        if (claudeFallback) {
            issues.add(new ReadinessIssue("CLAUDE_CONFIG_FALLBACK", "warning", "Claude Agent 仍使用部署环境配置，请迁移到系统页面"));
        }

        var stages = List.of(
                new ReadinessStage("prd", prdReady, modelDetail(targetReports, "prd", prdReady)),
                new ReadinessStage("planning", planningReady, modelDetail(targetReports, "planning", planningReady)),
                new ReadinessStage("codeExecution", workerReady && !"fake".equals(executionProvider),
                        executionDetail(executionProvider, targetReports)),
                new ReadinessStage("repository", repositoryReady,
                        repositoryReady ? (gitlabMode ? "GitLab 项目可访问" : "Git 仓库可访问") : "仓库不可访问"),
                new ReadinessStage("validation", validationReady,
                        validationReady ? (gitConfig != null && "skip".equals(gitConfig.validationMode())
                                ? "由 MR CI 与人工验证" : "测试命令已配置") : "缺少测试命令")
        );
        var checkedAt = live.stream().map(WorkerReadinessReport::checkedAt).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new SystemReadiness(profile.systemId(), issues.stream().noneMatch(issue -> "error".equals(issue.severity())),
                checkedAt, executionProvider, stages, issues);
    }

    private void error(List<ReadinessIssue> issues, String code, String message) {
        issues.add(new ReadinessIssue(code, "error", message));
    }

    private String executionDetail(String provider, List<WorkerTarget> targets) {
        if (provider == null || provider.isBlank()) return "未配置";
        if ("http".equals(provider)) {
            return targets.stream().filter(item -> item.target().diffModelReady()).findFirst()
                    .map(item -> "单次 Diff，" + value(item.target().diffModel())).orElse("单次 Diff 模型不可用");
        }
        if ("deepagents".equals(provider)) {
            return targets.stream().filter(item -> item.target().deepagentsReady()).findFirst()
                    .map(item -> "Deep Agents，系统模型配置 " + value(item.target().deepagentsModel()))
                    .orElse("Deep Agents，模型配置不可用");
        }
        if (!"claude_sdk".equals(provider)) return provider;
        return targets.stream()
                .filter(item -> item.target().claudeReady())
                .findFirst()
                .map(item -> "worker_env".equals(item.target().claudeConfigSource())
                        ? "Claude Agent SDK，部署环境兼容配置"
                        : "Claude Agent SDK，系统模型配置 " + value(item.target().claudeModel()))
                .orElse("Claude Agent SDK，模型配置不可用");
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String modelDetail(List<WorkerTarget> targets, String stage, boolean ready) {
        if (!ready) return "业务模型不可用";
        return targets.stream().filter(item -> "prd".equals(stage) ? item.target().prdModelReady() : item.target().planningModelReady())
                .findFirst()
                .map(item -> "prd".equals(stage) ? value(item.target().prdModel()) : value(item.target().planningModel()))
                .filter(value -> !value.isBlank())
                .orElse("业务模型已配置");
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private List<String> readList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private String developerEngine(Map<String, Object> config) {
        if (config.get("agents") instanceof List<?> agents) {
            for (var item : agents) {
                if (item instanceof Map<?, ?> agent && "developer".equals(String.valueOf(agent.get("name")))) {
                    return value(agent.get("engine"));
                }
            }
        }
        return "http";
    }

    private record WorkerTarget(WorkerReadinessReport worker, TargetReadiness target) {
    }

    public record WorkerReadinessReport(String workerId, String taskQueue, String defaultExecutionProvider,
                                        List<String> capabilities, boolean httpProviderReachable, boolean releasePush,
                                        Instant checkedAt, List<TargetReadiness> targets, Instant receivedAt) {
        public WorkerReadinessReport withReceivedAt(Instant value) {
            return new WorkerReadinessReport(workerId, taskQueue, defaultExecutionProvider,
                    capabilities == null ? List.of() : capabilities, httpProviderReachable, releasePush,
                    checkedAt, targets == null ? List.of() : targets, value);
        }
    }

    public record TargetReadiness(String systemId, boolean repositoryAccessible, boolean gitRepository,
                                  boolean modelReady, String model, boolean claudeReady,
                                  String claudeModel, String claudeConfigSource,
                                  boolean prdModelReady, String prdModel,
                                  boolean planningModelReady, String planningModel,
                                  boolean diffModelReady, String diffModel,
                                  boolean deepagentsReady, String deepagentsModel) {
        public TargetReadiness(String systemId, boolean repositoryAccessible, boolean gitRepository,
                               boolean modelReady, String model, boolean claudeReady,
                               String claudeModel, String claudeConfigSource,
                               boolean prdModelReady, String prdModel,
                               boolean planningModelReady, String planningModel,
                               boolean diffModelReady, String diffModel) {
            this(systemId, repositoryAccessible, gitRepository, modelReady, model, claudeReady, claudeModel,
                    claudeConfigSource, prdModelReady, prdModel, planningModelReady, planningModel,
                    diffModelReady, diffModel, false, "");
        }

        public TargetReadiness(String systemId, boolean repositoryAccessible, boolean gitRepository,
                               boolean modelReady, String model, boolean claudeReady,
                               String claudeModel, String claudeConfigSource) {
            this(systemId, repositoryAccessible, gitRepository, modelReady, model, claudeReady, claudeModel,
                    claudeConfigSource, modelReady, model, modelReady, model, modelReady, model, false, "");
        }
    }

    public record SystemReadiness(String systemId, boolean ready, Instant checkedAt, String effectiveExecutionProvider,
                                  List<ReadinessStage> stages, List<ReadinessIssue> issues) {
    }

    public record ReadinessStage(String name, boolean ready, String detail) {
    }

    public record ReadinessIssue(String code, String severity, String message) {
    }
}
