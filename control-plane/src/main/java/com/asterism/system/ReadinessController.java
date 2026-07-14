package com.asterism.system;

import com.asterism.identity.SystemAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/systems")
public class ReadinessController {
    private final SystemProfileRepository systems;
    private final SystemAccessService access;
    private final ExecutionReadinessService readiness;

    public ReadinessController(SystemProfileRepository systems, SystemAccessService access, ExecutionReadinessService readiness) {
        this.systems = systems;
        this.access = access;
        this.readiness = readiness;
    }

    @GetMapping("/{systemId}/readiness")
    ExecutionReadinessService.SystemReadiness readiness(@PathVariable String systemId, Authentication actor) {
        access.requireMember(systemId, actor);
        var profile = systems.findById(systemId).orElseThrow(() -> new IllegalArgumentException("系统不存在"));
        return readiness.readiness(profile);
    }
}
