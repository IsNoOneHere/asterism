package com.agentteam.v5.system;

import com.agentteam.v5.identity.SystemAccessService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v5/systems/{systemId}")
public class BusinessModelController {
    private final SystemAccessService access;
    private final BusinessModelConfigService models;

    public BusinessModelController(SystemAccessService access, BusinessModelConfigService models) {
        this.access = access;
        this.models = models;
    }

    @GetMapping("/business-models")
    BusinessModelConfigService.BusinessModelPoolResponse list(@PathVariable String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        return models.get(systemId);
    }

    @PostMapping("/business-models")
    BusinessModelConfigService.BusinessModelPoolResponse create(@PathVariable String systemId,
            @Valid @RequestBody BusinessModelConfigService.BusinessModelRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return models.create(systemId, request);
    }

    @PatchMapping("/business-models/{modelId}")
    BusinessModelConfigService.BusinessModelPoolResponse update(@PathVariable String systemId, @PathVariable String modelId,
            @Valid @RequestBody BusinessModelConfigService.BusinessModelRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return models.update(systemId, modelId, request);
    }

    @DeleteMapping("/business-models/{modelId}")
    BusinessModelConfigService.BusinessModelPoolResponse delete(@PathVariable String systemId, @PathVariable String modelId,
                                                                  Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return models.delete(systemId, modelId);
    }

    @PatchMapping("/business-model-routing")
    BusinessModelConfigService.BusinessModelPoolResponse routing(@PathVariable String systemId,
            @Valid @RequestBody BusinessModelConfigService.RoutingRequest request, Authentication actor) {
        access.requireOwnerOrAdmin(systemId, actor);
        return models.updateRouting(systemId, request);
    }
}
