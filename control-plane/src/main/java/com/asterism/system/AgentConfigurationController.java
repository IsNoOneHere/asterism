package com.asterism.system;

import com.asterism.identity.SystemAccessService;
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

    @PostMapping("/model-profiles/{profileId}/connection-test")
    ModelConnectionClient.ConnectionResult testProfileConnection(@PathVariable String systemId,
            @PathVariable String profileId, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.testProfile(systemId, profileId);
    }

    @PatchMapping("/agents/{agentName}")
    AgentConfigurationService.AgentConfigurationResponse updateAgent(@PathVariable String systemId,
            @PathVariable String agentName, @RequestBody AgentConfigurationService.AgentRequest request,
            Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.updateAgent(systemId, agentName, request);
    }

    @PatchMapping("/agent-config/settings")
    AgentConfigurationService.AgentConfigurationResponse updateExecutionSettings(@PathVariable String systemId,
            @RequestBody AgentConfigurationService.ExecutionSettingsRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return config.updateExecutionSettings(systemId, request);
    }

}
