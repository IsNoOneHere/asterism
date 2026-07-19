package com.asterism.system;

import com.asterism.IntegrationDatabase;
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

@SpringBootTest(properties = "asterism.worker-callback.token=test-worker-token")
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
                .andExpect(jsonPath("$.agents[0].name").value("product"))
                .andExpect(jsonPath("$.agents[1].name").value("developer"))
                .andExpect(jsonPath("$.engines[0]").value("claude_sdk_team"))
                .andExpect(jsonPath("$.modelRouting").doesNotExist())
                .andExpect(content().string(not(containsString("profile-secret"))))
                .andExpect(content().string(not(containsString("\"apiKey\":"))))
                .andReturn().getResponse().getContentAsString();
        var profileId = objectMapper.readTree(profileResponse).get("modelProfiles").get(0).get("id").asText();

        mockMvc.perform(patch("/api/v5/systems/" + systemId + "/agents/product")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"product","modelProfileRef":"%s"}
                                """.formatted(profileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].modelProfileRef").value(profileId));

        mockMvc.perform(patch("/api/v5/systems/" + systemId + "/agents/developer")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"developer","engine":"claude_sdk_team","modelProfileRef":"%s",
                                 "pathScope":["src"],"maxTurns":12,"timeoutSeconds":300}
                                """.formatted(profileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[1].engine").value("claude_sdk_team"));

        mockMvc.perform(post("/api/v5/systems/" + systemId + "/agents")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"frontend-dev","engine":"claude_sdk_team","modelProfileRef":"%s",
                                 "pathScope":["web"],"prompt":"只改前端","maxTurns":12,"timeoutSeconds":300}
                                """.formatted(profileId)))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/v5/systems/" + systemId + "/agents/product"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/api/v5/systems"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("profile-secret"))))
                .andExpect(content().string(not(containsString("\"apiKey\":"))));

        mockMvc.perform(get("/api/v5/internal/systems/" + systemId + "/model-config")
                        .queryParam("agent", "product")
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("claude-sonnet"))
                .andExpect(jsonPath("$.model_profiles[0].api_key").value("profile-secret"))
                .andExpect(jsonPath("$.agents[0].name").value("product"))
                .andExpect(jsonPath("$.agents[0].model_profile_ref").value(profileId));
    }
}
