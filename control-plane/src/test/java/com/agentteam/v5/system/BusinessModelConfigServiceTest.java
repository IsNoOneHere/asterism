package com.agentteam.v5.system;

import com.agentteam.v5.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessModelConfigServiceTest {
    private final AtomicReference<SystemProfile> current = new AtomicReference<>();
    private final SystemProfileRepository systems = mock(SystemProfileRepository.class);
    private final JdbcAggregateTemplate aggregate = mock(JdbcAggregateTemplate.class);
    private final SystemConfigLock lock = mock(SystemConfigLock.class);
    private final BusinessModelConfigService service = new BusinessModelConfigService(systems, aggregate, new ObjectMapper(), lock);

    @BeforeEach
    void setUp() {
        current.set(profile("""
                {"provider":"deepseek","model":"deepseek-chat","baseUrl":"https://api.deepseek.com","apiKey":"legacy-secret"}
                """));
        when(systems.findById("sys-1")).thenAnswer(call -> Optional.of(current.get()));
        when(aggregate.update(any(SystemProfile.class))).thenAnswer(call -> {
            var saved = call.getArgument(0, SystemProfile.class);
            current.set(saved);
            return saved;
        });
    }

    @Test
    void readsLegacyConfigAndMaterializesWhenAddingSecondModel() {
        var legacy = service.get("sys-1");
        assertThat(legacy.models()).singleElement().satisfies(model -> {
            assertThat(model.modelId()).isEqualTo("legacy-default");
            assertThat(model.apiKeyConfigured()).isTrue();
        });

        var result = service.create("sys-1", request("OpenAI 主模型", "openai", "gpt-4.1", "new-secret"));

        assertThat(result.models()).extracting(BusinessModelConfigService.BusinessModelView::name)
                .containsExactly("默认业务模型", "OpenAI 主模型");
        assertThat(current.get().modelProviderConfig())
                .contains("businessModels")
                .contains("legacy-secret")
                .doesNotContain("\"provider\":\"deepseek\",\"model\"");
        verify(lock).lockBusinessModels("sys-1");
    }

    @Test
    void routesEachStageWithoutAutomaticFallback() {
        var created = service.create("sys-1", request("规划模型", "openai", "gpt-plan", "plan-secret"));
        var planningId = created.models().stream().filter(model -> model.name().equals("规划模型")).findFirst().orElseThrow().modelId();

        service.updateRouting("sys-1", new BusinessModelConfigService.RoutingRequest(
                "legacy-default", "", planningId, planningId));

        assertThat(service.resolve("sys-1", "prd").model()).isEqualTo("deepseek-chat");
        assertThat(service.resolve("sys-1", "planning").model()).isEqualTo("gpt-plan");
        assertThat(service.resolve("sys-1", "diff").modelId()).isEqualTo(planningId);
    }

    @Test
    void blankKeyUpdatePreservesSecretAndDuplicateNameIsRejected() {
        service.update("sys-1", "legacy-default", request("默认业务模型", "deepseek", "deepseek-v4", ""));
        assertThat(current.get().modelProviderConfig()).contains("legacy-secret").contains("deepseek-v4");

        service.create("sys-1", request("第二模型", "openai", "gpt-4.1", "key-2"));
        assertThatThrownBy(() -> service.create("sys-1", request("第二模型", "openai", "gpt-4.1-mini", "key-3")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("MODEL_NAME_EXISTS");
    }

    @Test
    void modelReferencedByRoutingOrClaudeCannotBeDeleted() {
        assertThatThrownBy(() -> service.delete("sys-1", "legacy-default"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("MODEL_IN_USE");

        service.updateClaude("sys-1", new SystemController.ClaudeModelConfigRequest(
                "deepseek", "deepseek-reasoner", "https://api.deepseek.com/anthropic", true,
                "legacy-default", ""));
        assertThat(service.resolveClaudeKey("sys-1").apiKey()).isEqualTo("legacy-secret");
    }

    private BusinessModelConfigService.BusinessModelRequest request(String name, String preset, String model, String key) {
        return new BusinessModelConfigService.BusinessModelRequest(name, preset, model, "https://example.com", key);
    }

    private SystemProfile profile(String config) {
        var now = Instant.now();
        return new SystemProfile("sys-1", "系统", "", "/repo", "owner", "[]", "[]", "[]", "{}",
                config, "admin", now, now);
    }
}
