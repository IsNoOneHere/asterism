package com.agentteam.v5.system;

import com.agentteam.v5.identity.SystemAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v5/systems/{systemId}")
public class AgentConfigurationController {
    private final SystemAccessService access;
    private final AgentConfigurationService config;

    public AgentConfigurationController(SystemAccessService access, AgentConfigurationService config) {
        this.access = access;
        this.config = config;
    }

    @GetMapping("/agent-config")
    AgentConfigurationService.AgentConfigurationResponse get(@PathVariable String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        return config.get(systemId);
    }

    @PostMapping("/model-profiles")
    AgentConfigurationService.AgentConfigurationResponse createProfile(@PathVariable String systemId,
            @RequestBody AgentConfigurationService.ModelProfileRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.createProfile(systemId, request);
    }

    @PatchMapping("/model-profiles/{profileId}")
    AgentConfigurationService.AgentConfigurationResponse updateProfile(@PathVariable String systemId,
            @PathVariable String profileId, @RequestBody AgentConfigurationService.ModelProfileRequest request,
            Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.updateProfile(systemId, profileId, request);
    }

    @DeleteMapping("/model-profiles/{profileId}")
    AgentConfigurationService.AgentConfigurationResponse deleteProfile(@PathVariable String systemId,
            @PathVariable String profileId, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.deleteProfile(systemId, profileId);
    }

    @PostMapping("/agent-roles")
    AgentConfigurationService.AgentConfigurationResponse createRole(@PathVariable String systemId,
            @RequestBody AgentConfigurationService.AgentRoleRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.createRole(systemId, request);
    }

    @PatchMapping("/agent-roles/{roleId}")
    AgentConfigurationService.AgentConfigurationResponse updateRole(@PathVariable String systemId,
            @PathVariable String roleId, @RequestBody AgentConfigurationService.AgentRoleRequest request,
            Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.updateRole(systemId, roleId, request);
    }

    @DeleteMapping("/agent-roles/{roleId}")
    AgentConfigurationService.AgentConfigurationResponse deleteRole(@PathVariable String systemId,
            @PathVariable String roleId, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.deleteRole(systemId, roleId);
    }

    @PatchMapping("/default-agent-role")
    AgentConfigurationService.AgentConfigurationResponse updateDefaultRole(@PathVariable String systemId,
            @RequestBody AgentConfigurationService.DefaultRoleRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.updateDefaultRole(systemId, request);
    }
}
