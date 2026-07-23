package com.asterism.system;

import com.asterism.git.GitIntegrationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalSystemControllerTest {
    @Test
    void visionBindingDoesNotDependOnProfileOrder() {
        var configurations = mock(AgentConfigurationService.class);
        var first = new AgentConfigurationService.ModelProfile(
                "mp-first", "First", "openai-compat", "", "key", "first", true,
                "json_object", true);
        var selected = new AgentConfigurationService.ModelProfile(
                "mp-selected", "Selected", "openai-compat", "", "key", "selected", true,
                "json_object", true);
        when(configurations.internal("sys-1")).thenReturn(new AgentConfigurationService.InternalAgentConfiguration(
                List.of(first, selected),
                List.of(
                        new AgentConfigurationService.Agent("product", "builtin", "", "mp-first",
                                List.of(), "", null, null),
                        new AgentConfigurationService.Agent("vision", "builtin", "", "mp-selected",
                                List.of(), "", null, null),
                        new AgentConfigurationService.Agent("developer", "builtin", "claude_sdk_team", "",
                                List.of(), "", 50, 600)),
                5));
        var controller = new InternalSystemController(configurations, mock(GitIntegrationService.class));

        var response = controller.modelConfig("sys-1", "vision", "");

        assertThat(response.get("model_id")).isEqualTo("mp-selected");
        assertThat(response.get("model")).isEqualTo("selected");
    }
}
