package com.asterism.git;

import com.asterism.identity.SystemAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/systems/{systemId}/git-config")
public class GitIntegrationController {
    private final GitIntegrationService git;
    private final SystemAccessService access;

    public GitIntegrationController(GitIntegrationService git, SystemAccessService access) {
        this.git = git;
        this.access = access;
    }

    @GetMapping
    GitIntegrationService.PublicGitConfiguration get(@PathVariable String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        return git.get(systemId);
    }

    @PutMapping
    GitIntegrationService.PublicGitConfiguration update(@PathVariable String systemId,
                                                        @RequestBody GitIntegrationService.UpdateGitConfiguration request,
                                                        Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return git.update(systemId, request);
    }
}
