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
import java.util.Map;

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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        var firstResponse = objectMapper.readTree(first);
        var prdId = firstResponse.get("prdId").asText();
        completeProductExecution(firstResponse.get("executionId").asText(), Map.of(
                "title", "登录页错误提示",
                "goal", "给登录页加错误提示",
                "scope", "code_change",
                "acceptanceCriteria", List.of()));

        var second = postJson("/api/v5/systems/" + systemId + "/prd/messages",
                "{\"prdId\":\"" + prdId + "\",\"content\":\"错误密码时显示明确错误提示\"}", "e2e-user")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        completeProductExecution(objectMapper.readTree(second).get("executionId").asText(), Map.of(
                "acceptanceCriteria", List.of("错误密码时显示明确错误提示")));
        mockMvc.perform(get("/api/v5/prd-sessions/" + prdId).with(httpBasic("e2e-user", "asterism")))
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
        var requirementManifestId = confirm.get("requirementManifestId").asText();
        var productArtifactId = confirm.get("productArtifactId").asText();
        var productArtifact = artifactRef(productArtifactId, "e2e-owner");

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
        var planningTransitionId = caseId + ":integration:planning";
        var planning = postWorkerJson(
                systemId, caseId, prdId, workItemId, "CodingPlanProposed", 2,
                Map.of(
                        "summary", "计划已生成",
                        "planMarkdown", "# 执行计划",
                        "baseRevisions", Map.of("main", "base-1")),
                Map.of(
                        "kind", "ProposePlanningArtifact",
                        "transitionId", planningTransitionId,
                        "parent", productArtifact,
                        "content", Map.of(
                                "planMarkdown", "# 执行计划",
                                "baseRevisions", Map.of("main", "base-1"),
                                "acceptanceCriteriaRefs", List.of("AC-1"),
                                "repositories", List.of("main"),
                                "evidenceRefs", List.of("git:main@base-1"),
                                "risks", List.of(),
                                "openQuestions", List.of())),
                executionEvidence("PlanningExecution", planningTransitionId, "plan-session-it"))
                .path("artifactRef");
        var approvedPlanning = postWorkerJson(
                systemId, caseId, prdId, workItemId, "CodingPlanApproved", 3,
                Map.of("summary", "计划已批准"),
                Map.of(
                        "kind", "ApprovePlanningArtifact",
                        "transitionId", caseId + ":integration:approve-planning",
                        "artifact", planning),
                null)
                .path("artifactRef");

        var codingTransitionId = caseId + ":integration:coding";
        var diff = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -old
                +new
                """;
        var repoChange = Map.of(
                "repo", "main",
                "diffPatch", diff,
                "changedPaths", List.of("src/App.java"),
                "summary", "增加错误提示");
        var coding = postWorkerJson(
                systemId, caseId, prdId, workItemId, "ModificationCompleted", 4,
                Map.of(
                        "summary", "代码修改完成",
                        "diffPatch", diff,
                        "changedPaths", List.of("src/App.java"),
                        "repoDiffs", List.of(repoChange)),
                Map.of(
                        "kind", "ProposeCodingArtifact",
                        "transitionId", codingTransitionId,
                        "parent", approvedPlanning,
                        "content", Map.of(
                                "summary", "代码修改完成",
                                "repoChanges", List.of(repoChange),
                                "executionOutcome", Map.of("status", "completed", "blockers", List.of()),
                                "baseRevisions", Map.of("main", "base-1"))),
                executionEvidence("CodingExecution", codingTransitionId, "coding-session-it"))
                .path("artifactRef");

        postWorker(systemId, caseId, prdId, workItemId, "PatchApplied", 5,
                Map.of("summary", "补丁已应用"),
                null, evidence("PatchApplied", coding, null)).andExpect(status().isOk());
        var validationTransitionId = caseId + ":integration:validation";
        var approvedCoding = postWorkerJson(
                systemId, caseId, prdId, workItemId, "ValidationPassed", 6,
                Map.of("summary", "验证通过", "commands", List.of(
                        Map.of("command", "test", "exitCode", 0))),
                Map.of(
                        "kind", "ApproveCodingArtifact",
                        "transitionId", validationTransitionId,
                        "artifact", coding,
                        "note", "验证通过"),
                evidence("ValidationPassed", coding, validationTransitionId))
                .path("artifactRef");
        postWorker(systemId, caseId, prdId, workItemId, "ReleaseCompleted", 7,
                Map.of("summary", "发布完成"),
                null, evidence("ReleaseCompleted", approvedCoding, null)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v5/work-items/" + workItemId).with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("completed"));
        var snapshot = mockMvc.perform(post("/api/v5/context-snapshots")
                        .header("Authorization", "Bearer test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "systemId", systemId,
                                "prdId", prdId,
                                "workItemId", workItemId,
                                "requirementManifestId", requirementManifestId,
                                "phase", "planning",
                                "productArtifact", productArtifact,
                                "gitBaseRevisions", Map.of("main", "base-1")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(snapshot).get("requirementManifestId").asText())
                .isEqualTo(requirementManifestId);
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, String user) throws Exception {
        return mockMvc.perform(post(path)
                .with(httpBasic(user, "asterism"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void completeProductExecution(String executionId, Map<String, Object> patch) throws Exception {
        postProductExecutionEvent(executionId, Map.of(
                "event_id", "evt-product-started-" + executionId,
                "event_type", "Started",
                "stage", "running",
                "attempt", 1));
        postProductExecutionEvent(executionId, Map.of(
                "event_id", "evt-product-completed-" + executionId,
                "event_type", "Completed",
                "stage", "draft_completed",
                "attempt", 1,
                "result", Map.of(
                        "patch", patch,
                        "assistant_message", "PRD draft 已更新。",
                        "citations", Map.of())));
    }

    private void postProductExecutionEvent(String executionId, Map<String, Object> event) throws Exception {
        mockMvc.perform(post("/api/v5/internal/product-agent-executions/" + executionId + "/events")
                        .header("Authorization", "Bearer test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
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
                  "agentConfig": {},
                  "modelProviderConfig": {"provider": "fake"}
                }
                """.formatted(systemId), "admin").andExpect(status().isOk());
    }

    private void reportReadyWorker(String systemId) {
        var target = new ExecutionReadinessService.TargetReadiness(
                systemId, true, true, true, "prd-model", true, "claude-model", "system");
        readiness.report(new ExecutionReadinessService.WorkerReadinessReport(
                "test-worker", "asterism", "claude_sdk_team", List.of("claude_sdk_team"), false,
                Instant.now(), List.of(target), null));
    }

    private void addMembership(String systemId, String userId, String role) throws Exception {
        postJson("/api/v5/users/memberships", """
                {"systemId":"%s","userId":"%s","role":"%s"}
                """.formatted(systemId, userId, role), "admin").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postWorker(String systemId, String caseId, String prdId,
                                                                          String workItemId, String type, int step) throws Exception {
        return postWorker(
                systemId, caseId, prdId, workItemId, type, step,
                Map.of("summary", type), null, null);
    }

    private org.springframework.test.web.servlet.ResultActions postWorker(
            String systemId, String caseId, String prdId, String workItemId, String type, int step,
            Map<String, Object> payload, Map<String, Object> transition,
            Map<String, Object> evidence) throws Exception {
        return mockMvc.perform(post("/api/v5/projections")
                .header("Authorization", "Bearer test-worker-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(workerEvent(
                        systemId, caseId, prdId, workItemId, type, step,
                        payload, transition, evidence)));
    }

    private com.fasterxml.jackson.databind.JsonNode postWorkerJson(
            String systemId, String caseId, String prdId, String workItemId, String type, int step,
            Map<String, Object> payload, Map<String, Object> transition,
            Map<String, Object> evidence) throws Exception {
        var response = postWorker(
                systemId, caseId, prdId, workItemId, type, step,
                payload, transition, evidence)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String workerEvent(String systemId, String caseId, String prdId, String workItemId, String type, int step) {
        return workerEvent(
                systemId, caseId, prdId, workItemId, type, step,
                Map.of("summary", type), null, null);
    }

    private String workerEvent(
            String systemId, String caseId, String prdId, String workItemId, String type, int step,
            Map<String, Object> payload, Map<String, Object> transition,
            Map<String, Object> evidence) {
        var event = objectMapper.createObjectNode();
        event.put("eventType", type);
        event.put("systemId", systemId);
        event.put("caseId", caseId);
        event.put("prdId", prdId);
        event.put("workItemId", workItemId);
        event.put("actorId", "worker");
        event.set("payload", objectMapper.valueToTree(payload));
        event.put("correlationId", caseId);
        event.put("causationId", "sig-" + step);
        event.put("idempotencyKey", caseId + ":" + type + ":" + step);
        if (transition != null) event.set("artifactTransition", objectMapper.valueToTree(transition));
        if (evidence != null) event.set("artifactEvidence", objectMapper.valueToTree(evidence));
        return event.toString();
    }

    private com.fasterxml.jackson.databind.JsonNode artifactRef(String artifactId, String user) throws Exception {
        var response = mockMvc.perform(get("/api/v5/artifacts/" + artifactId)
                        .with(httpBasic(user, "asterism")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("artifact").path("ref");
    }

    private Map<String, Object> executionEvidence(
            String evidenceType, String transitionId, String sessionId) {
        return Map.of(
                "evidenceId", transitionId + ":evidence",
                "evidenceType", evidenceType,
                "transitionId", transitionId,
                "payload", Map.of(
                        "sessionId", sessionId,
                        "turns", 1,
                        "tokenUsage", Map.of("inputTokens", 1)));
    }

    private Map<String, Object> evidence(
            String evidenceType, com.fasterxml.jackson.databind.JsonNode artifact,
            String transitionId) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("evidenceId", caseEvidenceId(evidenceType, artifact, transitionId));
        result.put("artifact", artifact);
        result.put("evidenceType", evidenceType);
        if (transitionId != null) result.put("transitionId", transitionId);
        result.put("payload", Map.of("summary", evidenceType));
        return result;
    }

    private String caseEvidenceId(
            String evidenceType, com.fasterxml.jackson.databind.JsonNode artifact,
            String transitionId) {
        return transitionId == null
                ? artifact.path("artifactId").asText() + ":" + evidenceType + ":evidence"
                : transitionId + ":evidence";
    }
}
