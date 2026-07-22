package com.asterism.identity;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.event.ProjectionController;
import com.asterism.context.ContextController;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.memory.MemoryController;
import com.asterism.memory.MemoryCandidateService;
import com.asterism.memory.WorkItemMemoryLearningService;
import com.asterism.memory.MemoryItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ProjectionController.class, MemoryController.class, ContextController.class})
@Import(SecurityConfig.class)
@EnableConfigurationProperties(WorkerCallbackProperties.class)
@TestPropertySource(properties = "asterism.worker-callback.token=test-worker-token")
class WorkerCallbackSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DomainEventService events;

    @MockBean
    private MemoryItemRepository memories;

    @MockBean
    private RequirementContextManifestService manifests;

    @MockBean
    private JdbcAggregateTemplate aggregate;

    @MockBean
    private MemoryCandidateService memoryCandidates;

    @MockBean
    private WorkItemMemoryLearningService memoryLearning;

    @MockBean
    private SystemAccessService access;

    @Test
    void workerProjectionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v5/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workerProjectionWithTokenReturns200() throws Exception {
        when(events.append(any())).thenReturn(new DomainEventRecord(
                1L,
                "evt-1",
                "WorkItemActivated",
                "v5.0",
                "sys-1",
                "case-1",
                "prd-1",
                "wi-1",
                "worker",
                "worker",
                "{}",
                "case-1",
                null,
                "case-1:WorkItemActivated:sig-1",
                Instant.now()));

        mockMvc.perform(post("/api/v5/projections")
                        .header("Authorization", "Bearer test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isOk());
    }

    @Test
    void workerContextSnapshotWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v5/context-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                        .content(contextRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workerContextSnapshotWithTokenReturns200() throws Exception {
        when(manifests.executionSnapshot("sys-1", "prd-1", "wi-1", "manifest-1", "目标", java.util.Map.of()))
                .thenReturn(new RequirementContextManifestService.ExecutionContextSnapshot(
                        "sys-1", "manifest-1", List.of(), "bundle-1", List.of(), List.of()));
        mockMvc.perform(post("/api/v5/context-snapshots")
                        .header("X-Agent-Team-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextRequest()))
                .andExpect(status().isOk());
    }

    @Test
    void defaultWorkerTokenIsRejectedOutsideLocalProfile() {
        assertThatThrownBy(() -> new WorkerCallbackProperties("dev-worker-token", "prod").requiredToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认 worker token");
    }

    private String payload() {
        return """
                {
                  "eventType": "WorkItemActivated",
                  "systemId": "sys-1",
                  "caseId": "case-1",
                  "prdId": "prd-1",
                  "workItemId": "wi-1",
                  "payload": {},
                  "correlationId": "case-1",
                  "idempotencyKey": "case-1:WorkItemActivated:sig-1"
                }
                """;
    }

    private String contextRequest() {
        return """
                {"systemId":"sys-1","prdId":"prd-1","workItemId":"wi-1",
                 "requirementManifestId":"manifest-1","goal":"目标","draft":{}}
                """;
    }
}
