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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "agent-team.worker-callback.token=test-worker-token")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class BusinessModelApiIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void createsTwoModelsAndResolvesPlanningRouteWithoutPersistingTestData() throws Exception {
        var systemId = "sys-model-test-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v5/systems")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"systemId":"%s","name":"模型路由测试","repoPath":"/tmp","ownerUserId":"admin",
                                 "allowedPaths":[],"forbiddenPaths":[],"testCommands":["true"],"agentConfig":{},
                                 "modelProviderConfig":{"provider":"deepseek","model":"prd-model","apiKey":"prd-secret"}}
                                """.formatted(systemId)))
                .andExpect(status().isOk());

        var created = mockMvc.perform(post("/api/v5/systems/" + systemId + "/business-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"规划模型","preset":"openai","model":"planning-model",
                                 "baseUrl":"https://example.com","apiKey":"planning-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("planning-secret"))))
                .andReturn().getResponse().getContentAsString();
        var body = objectMapper.readTree(created);
        var planningId = body.get("models").get(1).get("modelId").asText();

        mockMvc.perform(patch("/api/v5/systems/" + systemId + "/business-model-routing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultModelId":"legacy-default","planningModelId":"%s"}
                                """.formatted(planningId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveRouting.prdModelId").value("legacy-default"))
                .andExpect(jsonPath("$.effectiveRouting.planningModelId").value(planningId));

        mockMvc.perform(get("/api/v5/internal/systems/" + systemId + "/model-config")
                        .param("stage", "planning")
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("planning-model"))
                .andExpect(jsonPath("$.api_key").value("planning-secret"));
    }
}
