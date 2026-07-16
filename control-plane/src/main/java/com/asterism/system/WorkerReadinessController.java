package com.asterism.system;

import com.asterism.git.GitIntegrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/v5/internal")
public class WorkerReadinessController {
    private final SystemProfileRepository systems;
    private final ExecutionReadinessService readiness;
    private final GitIntegrationService git;

    public WorkerReadinessController(SystemProfileRepository systems, ExecutionReadinessService readiness,
                                     GitIntegrationService git) {
        this.systems = systems;
        this.readiness = readiness;
        this.git = git;
    }

    @GetMapping("/execution-targets")
    List<ExecutionTarget> targets() {
        return StreamSupport.stream(systems.findAll().spliterator(), false)
                .map(system -> new ExecutionTarget(system.systemId(), system.repoPath(), git.get(system.systemId()).repos()))
                .toList();
    }

    @PostMapping("/worker-readiness")
    void report(@RequestBody ExecutionReadinessService.WorkerReadinessReport report) {
        // 心跳只保存在内存，控制面重启后由 Worker 自动补报。
        readiness.report(report);
    }

    public record ExecutionTarget(String systemId, String repoPath, List<GitIntegrationService.RepoConfig> repos) {
    }
}
