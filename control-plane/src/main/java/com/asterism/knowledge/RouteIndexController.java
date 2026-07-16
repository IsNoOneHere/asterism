package com.asterism.knowledge;

import com.asterism.git.GitIntegrationService;
import com.asterism.identity.SystemAccessService;
import com.asterism.system.SystemProfileRepository;
import com.asterism.temporal.TemporalCasePort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/systems/{systemId}/knowledge")
public class RouteIndexController {
    private final SystemAccessService access;
    private final SystemProfileRepository systems;
    private final TemporalCasePort temporal;
    private final GitIntegrationService git;

    public RouteIndexController(SystemAccessService access, SystemProfileRepository systems, TemporalCasePort temporal,
                                GitIntegrationService git) {
        this.access = access;
        this.systems = systems;
        this.temporal = temporal;
        this.git = git;
    }

    @PostMapping("/route-index")
    RouteIndexResponse start(@PathVariable String systemId, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        var system = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        var repos = git.internal(systemId).repos().stream().map(repo -> new TemporalCasePort.RepoSnapshot(
                repo.repoId(), repo.name(), repo.kind(), repo.gitlabProject(), repo.defaultBranch(), repo.cloneMode(),
                repo.localPath(), repo.allowedPaths(), repo.forbiddenPaths(), repo.testCommands())).toList();
        var workflowId = temporal.startRouteIndex(new TemporalCasePort.RouteIndexCommand(systemId, system.repoPath(), repos));
        return new RouteIndexResponse(workflowId);
    }

    public record RouteIndexResponse(String workflowId) {
    }
}
