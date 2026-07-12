package com.agentteam.v5.system;

import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.identity.SystemMembershipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemControllerSecretTest {
    @Test
    void apiKeyIsStoredButMaskedInResponse() {
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(aggregate.insert(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        when(repo.existsById("sys-1")).thenReturn(false);
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), mock(BusinessModelConfigService.class));

        var response = controller.create(request(Map.of("provider", "openai", "apiKey", "secret-key")),
                new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().modelProviderConfig()).contains("secret-key");
        assertThat(response.modelProviderConfig()).contains("******").doesNotContain("secret-key");
    }

    @Test
    void nestedBusinessModelKeysAreMasked() {
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(aggregate.insert(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), mock(BusinessModelConfigService.class));
        var nested = Map.<String, Object>of("businessModels", List.of(Map.of(
                "modelId", "bm-1", "name", "主模型", "apiKey", "nested-secret")));

        var response = controller.create(request(nested), new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().modelProviderConfig()).contains("nested-secret");
        assertThat(response.modelProviderConfig()).contains("******").doesNotContain("nested-secret");
    }

    @Test
    void blankApiKeyUpdateKeepsExistingSecret() {
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(repo.findById("sys-1")).thenReturn(Optional.of(existing()));
        when(aggregate.update(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), mock(BusinessModelConfigService.class));

        controller.update("sys-1", request(Map.of("provider", "openai")),
                new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().modelProviderConfig()).contains("old-secret");
    }

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
                aggregate, new ObjectMapper(), mock(BusinessModelConfigService.class));
        var request = new SystemController.UpsertSystemRequest("sys-1", "Demo", "demo", "/repo", "owner",
                List.of(), List.of(), List.of("git diff --check"), null, null);

        controller.update("sys-1", request, new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().agentConfig()).contains("claude_sdk");
        assertThat(saved.get().modelProviderConfig()).contains("old-secret");
    }

    @Test
    void claudeConfigCanReuseBusinessKeyAndMasksSecrets() {
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(repo.findById("sys-1")).thenReturn(Optional.of(existing()));
        when(aggregate.update(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), businessModels(repo, aggregate));

        var response = controller.updateClaudeModelConfig("sys-1",
                new SystemController.ClaudeModelConfigRequest("deepseek", "deepseek-v4-pro",
                        "https://api.deepseek.com/anthropic", true, "", ""),
                new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().modelProviderConfig())
                .contains("deepseek-v4-pro")
                .contains("claudeReuseBusinessApiKey\":true")
                .contains("old-secret");
        assertThat(response.modelProviderConfig()).contains("******").doesNotContain("old-secret");
        assertThat(response.modelProviderConfig()).contains("claudeReuseBusinessApiKey\":true");

        when(repo.findById("sys-1")).thenReturn(Optional.of(saved.get()));
        var internal = new InternalSystemController(repo, new ObjectMapper(), businessModels(repo, aggregate),
                mock(AgentConfigurationService.class));
        assertThat(internal.claudeModelConfig("sys-1"))
                .containsEntry("configured", true)
                .containsEntry("source", "system_reuse")
                .containsEntry("api_key", "old-secret");
    }

    @Test
    void dedicatedClaudeKeyIsPreservedWhenRequestKeyIsBlank() {
        var current = existingWithModelConfig("""
                {"apiKey":"business-secret","claudeApiKey":"claude-secret","claudeReuseBusinessApiKey":false}
                """);
        var saved = new AtomicReference<SystemProfile>();
        var repo = mock(SystemProfileRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(repo.findById("sys-1")).thenReturn(Optional.of(current));
        when(aggregate.update(any(SystemProfile.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var controller = new SystemController(repo, mock(SystemMembershipRepository.class), mock(SystemAccessService.class),
                aggregate, new ObjectMapper(), businessModels(repo, aggregate));

        var response = controller.updateClaudeModelConfig("sys-1",
                new SystemController.ClaudeModelConfigRequest("anthropic", "claude-sonnet-4-5", "", false, "", ""),
                new UsernamePasswordAuthenticationToken("admin", "n/a"));

        assertThat(saved.get().modelProviderConfig()).contains("claude-secret");
        assertThat(response.modelProviderConfig())
                .contains("claudeApiKey\":\"******")
                .contains("claudeReuseBusinessApiKey\":false")
                .doesNotContain("claude-secret");
    }

    private BusinessModelConfigService businessModels(SystemProfileRepository repo, JdbcAggregateTemplate aggregate) {
        return new BusinessModelConfigService(repo, aggregate, new ObjectMapper(), mock(SystemConfigLock.class));
    }

    private SystemController.UpsertSystemRequest request(Map<String, Object> modelConfig) {
        return new SystemController.UpsertSystemRequest(
                "sys-1", "Demo", "demo", "/repo", "owner",
                List.of("src"), List.of("secrets"), List.of("mvn test"),
                Map.of("provider", "fake"), modelConfig);
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
