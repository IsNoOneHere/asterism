package com.agentteam.v5.system;

import com.agentteam.v5.IntegrationDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "agent-team.worker-callback.token=test-worker-token")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class AgentConfigurationApiIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void publicResponsesOnlyExposeApiKeySetAndInternalReturnsCompleteConfig() throws Exception {
        var systemId = "sys-agent-config-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v5/systems").contentType(MediaType.APPLICATION_JSON).content("""
                {"systemId":"%s","name":"三层配置测试","repoPath":"/tmp","ownerUserId":"admin",
                 "allowedPaths":[],"forbiddenPaths":[],"testCommands":["true"],"agentConfig":{},"modelProviderConfig":{}}
                """.formatted(systemId))).andExpect(status().isOk());

        var profileResponse = mockMvc.perform(post("/api/v5/systems/" + systemId + "/model-profiles")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Claude","provider":"anthropic","model":"claude-sonnet",
                                 "baseUrl":"https://example.invalid","apiKey":"profile-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelProfiles[0].apiKeySet").value(true))
                .andExpect(content().string(not(containsString("profile-secret"))))
                .andExpect(content().string(not(containsString("\"apiKey\":"))))
                .andReturn().getResponse().getContentAsString();
        var profileId = objectMapper.readTree(profileResponse).get("modelProfiles").get(0).get("id").asText();

        mockMvc.perform(post("/api/v5/systems/" + systemId + "/agent-roles")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"前端 Agent","engine":"claude_sdk","modelProfileRef":"%s",
                                 "pathScope":["web"],"prompt":"只改前端","maxTurns":12,"timeoutSeconds":300}
                                """.formatted(profileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRoles[0].engine").value("claude_sdk"));

        mockMvc.perform(get("/api/v5/systems"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("profile-secret"))))
                .andExpect(content().string(not(containsString("\"apiKey\":"))));

        mockMvc.perform(get("/api/v5/internal/systems/" + systemId + "/model-config")
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("claude-sonnet"))
                .andExpect(jsonPath("$.model_profiles[0].api_key").value("profile-secret"))
                .andExpect(jsonPath("$.agent_roles[0].model_profile_ref").value(profileId));
    }
}
