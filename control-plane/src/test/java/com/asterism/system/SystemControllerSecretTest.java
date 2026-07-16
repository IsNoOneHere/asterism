package com.asterism.system;

import com.asterism.identity.SystemAccessService;
import com.asterism.identity.SystemMembershipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemControllerSecretTest {
    @Test
    void legacyPutKeepsAgentConfigWhenRequestOmitsIt() {
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var current = existing();
        current = new SystemProfile(current.systemId(), current.name(), current.description(), current.repoPath(),
                current.ownerUserId(), current.allowedPaths(), current.forbiddenPaths(), current.testCommands(),
                "{\"executionProvider\":\"claude_sdk\"}", current.modelProviderConfig(), current.createdBy(), current.createdAt(), current.updatedAt());
        when(repo.findById("sys-1")).thenReturn(Optional.of(current));
        when(aggregate.update(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), mock(com.asterism.temporal.TemporalCasePort.class),
                mock(com.asterism.git.GitIntegrationService.class));
        var request = new SystemController.UpsertSystemRequest("sys-1", "Demo", "demo", "/repo", "owner",
                List.of(), List.of(), List.of("git diff --check"));

        controller.update("sys-1", request, new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().agentConfig()).contains("claude_sdk");
        assertThat(saved.get().modelProviderConfig()).contains("old-secret");
    }

    private SystemProfile existing() {
        return existingWithModelConfig("{\"apiKey\":\"old-secret\"}");
    }

    private SystemProfile existingWithModelConfig(String modelProviderConfig) {
        var now = Instant.now();
        return new SystemProfile("sys-1", "Demo", "demo", "/repo", "owner",
                "[]", "[]", "[]", "{}", modelProviderConfig,
                "seed", now, now);
    }
}
