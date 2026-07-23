package com.asterism.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConfigurationServiceCompatibilityTest {
    @Test
    void loadsLegacyVisionFlagAndCreatesExplicitVisionBinding() {
        var systems = mock(SystemProfileRepository.class);
        var now = Instant.now();
        var config = """
                {"modelProfiles":[{"id":"mp-kimi","name":"Kimi","provider":"openai-compat",
                  "baseUrl":"https://models.example/v1","apiKey":"secret","model":"kimi",
                  "supportsVision":true}],
                 "agents":[{"name":"product","kind":"builtin","modelProfileRef":"mp-kimi"},
                           {"name":"developer","kind":"builtin","engine":"claude_sdk_team",
                            "modelProfileRef":"","pathScope":[],"prompt":""}]}
                """;
        when(systems.findById("sys-1")).thenReturn(Optional.of(new SystemProfile(
                "sys-1", "测试", "", "/tmp", "owner", "[]", "[]", "[]", "{}", config,
                "owner", now, now)));
        var service = new AgentConfigurationService(
                systems, mock(JdbcAggregateTemplate.class), new ObjectMapper(),
                mock(SystemConfigLock.class), mock(ModelConnectionClient.class));

        var loaded = service.internal("sys-1");

        assertThat(loaded.modelProfiles().get(0).imageInputEnabled()).isTrue();
        assertThat(loaded.modelProfiles().get(0).structuredOutput()).isEqualTo("json_object");
        assertThat(loaded.agents()).extracting(AgentConfigurationService.Agent::name)
                .containsExactly("product", "vision", "developer");
        assertThat(loaded.agents().get(1).modelProfileRef()).isEqualTo("mp-kimi");
    }
}
