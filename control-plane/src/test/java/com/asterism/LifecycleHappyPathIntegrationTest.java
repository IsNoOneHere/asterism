package com.asterism;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.asterism.system.ExecutionReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "asterism.worker-callback.token=test-worker-token")
@AutoConfigureMockMvc
class LifecycleHappyPathIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionReadinessService readiness;

    @Test
    void completeLifecycleWithFakeTemporalAndWorkerCallbacks() throws Exception {
        var systemId = "sys-it-" + UUID.randomUUID();
        createSystem(systemId);
        reportReadyWorker(systemId);
        addMembership(systemId, "e2e-user", "requester");
        addMembership(systemId, "e2e-owner", "owner");

        mockMvc.perform(get("/api/v5/auth/me").with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("e2e-user"));

        var first = postJson("/api/v5/systems/" + systemId + "/prd/messages",
                "{\"content\":\"给登录页加错误提示\"}", "e2e-user")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("need_clarification"))
                .andReturn().getResponse().getContentAsString();
        var prdId = objectMapper.readTree(first).get("prdId").asText();

        postJson("/api/v5/systems/" + systemId + "/prd/messages",
                "{\"prdId\":\"" + prdId + "\",\"content\":\"错误密码时显示明确错误提示\"}", "e2e-user")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("waiting_user_confirm"));

        var confirmed = mockMvc.perform(post("/api/v5/prd-sessions/" + prdId + "/confirm")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("waiting_owner_approval"))
                .andReturn().getResponse().getContentAsString();
        var confirm = objectMapper.readTree(confirmed);
        var workItemId = confirm.get("workItemId").asText();
        var caseId = confirm.get("caseId").asText();

        mockMvc.perform(post("/api/v5/work-items/" + workItemId + "/owner-approval")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v5/work-items/" + workItemId + "/owner-approval")
                        .with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v5/work-items/" + workItemId).with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("waiting_owner_approval"))
                .andExpect(jsonPath("$.executionAllowed").value(false));

        mockMvc.perform(post("/api/v5/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerEvent(systemId, caseId, prdId, workItemId, "WorkItemActivated", 1)))
                .andExpect(status().isUnauthorized());

        postWorker(systemId, caseId, prdId, workItemId, "WorkItemActivated", 1).andExpect(status().isOk());
        postWorker(systemId, caseId, prdId, workItemId, "ModificationCompleted", 2).andExpect(status().isOk());
        postWorker(systemId, caseId, prdId, workItemId, "PatchApplied", 3).andExpect(status().isOk());
        postWorker(systemId, caseId, prdId, workItemId, "ValidationPassed", 4).andExpect(status().isOk());
        postWorker(systemId, caseId, prdId, workItemId, "ReleaseCompleted", 5).andExpect(status().isOk());

        mockMvc.perform(get("/api/v5/work-items/" + workItemId).with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("completed"));
        var snapshot = mockMvc.perform(post("/api/v5/context-snapshots")
                        .header("Authorization", "Bearer test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemId\":\"" + systemId + "\",\"workItemId\":\"" + workItemId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(snapshot).get("approvedMemories")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, String user) throws Exception {
        return mockMvc.perform(post(path)
                .with(httpBasic(user, "asterism"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void createSystem(String systemId) throws Exception {
        postJson("/api/v5/systems", """
                {
                  "systemId": "%s",
                  "name": "Integration",
                  "description": "integration",
                  "repoPath": "/tmp/asterism-it",
                  "ownerUserId": "e2e-owner",
                  "allowedPaths": ["src"],
                  "forbiddenPaths": ["secrets"],
                  "testCommands": ["mvn test"],
                  "agentConfig": {"executionProvider": "http"},
                  "modelProviderConfig": {"provider": "fake"}
                }
                """.formatted(systemId), "admin").andExpect(status().isOk());
    }

    private void reportReadyWorker(String systemId) {
        var target = new ExecutionReadinessService.TargetReadiness(
                systemId, true, true, true, "test-model", false, "", "",
                true, "prd-model", true, "planning-model", true, "diff-model");
        readiness.report(new ExecutionReadinessService.WorkerReadinessReport(
                "test-worker", "asterism", "http", List.of("http"), true, false,
                Instant.now(), List.of(target), null));
    }

    private void addMembership(String systemId, String userId, String role) throws Exception {
        postJson("/api/v5/users/memberships", """
                {"systemId":"%s","userId":"%s","role":"%s"}
                """.formatted(systemId, userId, role), "admin").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postWorker(String systemId, String caseId, String prdId,
                                                                          String workItemId, String type, int step) throws Exception {
        return mockMvc.perform(post("/api/v5/projections")
                .header("Authorization", "Bearer test-worker-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(workerEvent(systemId, caseId, prdId, workItemId, type, step)));
    }

    private String workerEvent(String systemId, String caseId, String prdId, String workItemId, String type, int step) {
        return """
                {
                  "eventType": "%s",
                  "systemId": "%s",
                  "caseId": "%s",
                  "prdId": "%s",
                  "workItemId": "%s",
                  "actorId": "worker",
                  "payload": {"summary": "%s"},
                  "correlationId": "%s",
                  "causationId": "sig-%s",
                  "idempotencyKey": "%s:%s:%s"
                }
                """.formatted(type, systemId, caseId, prdId, workItemId, type, caseId, step, caseId, type, step);
    }
}
