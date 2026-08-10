package com.asterism.identity;

import com.asterism.artifact.ArtifactContextBuilder;
import com.asterism.artifact.ArtifactRef;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.artifact.ArtifactType;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.event.ProjectionController;
import com.asterism.context.ContextController;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.artifact.ArtifactService;
import com.asterism.memory.ArtifactMemoryLifecycleService;
import com.asterism.memory.MemoryCandidateRepository;
import com.asterism.memory.MemoryController;
import com.asterism.memory.MemoryCandidateService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.prd.ProductAgentExecution;
import com.asterism.prd.ProductAgentExecutionController;
import com.asterism.prd.ProductAgentExecutionService;
import com.asterism.prd.ProductAgentExecutionStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ProjectionController.class, MemoryController.class, ContextController.class,
        ProductAgentExecutionController.class})
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
    private MemoryCandidateRepository memoryCandidateRepository;

    @MockBean
    private RequirementContextManifestService manifests;

    @MockBean
    private ArtifactContextBuilder artifactContexts;

    @MockBean
    private ArtifactTransitionService artifactTransitions;

    @MockBean
    private JdbcAggregateTemplate aggregate;

    @MockBean
    private MemoryCandidateService memoryCandidates;

    @MockBean
    private ArtifactMemoryLifecycleService memoryLearning;

    @MockBean
    private ArtifactService artifactService;

    @MockBean
    private SystemAccessService access;

    @MockBean
    private ProductAgentExecutionService productExecutions;

    @Test
    void workerProjectionWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v5/projections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workerProjectionWithTokenReturns200() throws Exception {
        var saved = new DomainEventRecord(
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
                Instant.now());
        when(artifactTransitions.ingest(any(), any(), any(), any()))
                .thenReturn(new ArtifactTransitionService.Result(saved, null, null, null));

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
        var product = productRef();
        when(artifactContexts.build(any())).thenReturn(new ArtifactContextBuilder.ArtifactContextSnapshot(
                "snapshot-1", "snapshot-hash-1", "art-product-1",
                List.of(product), List.of(), java.util.Map.of(ArtifactType.PRODUCT, product),
                "sys-1", "manifest-1", List.of(), "bundle-1", List.of(),
                java.util.Map.of(), Instant.now(), List.of(), List.of(), product,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                null, null, null, null, List.of()));
        mockMvc.perform(post("/api/v5/context-snapshots")
                        .header("X-Agent-Team-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextRequest()))
                .andExpect(status().isOk());
    }

    @Test
    void productExecutionEventWithoutWorkerTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v5/internal/product-agent-executions/exec-1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productExecutionEvent()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productExecutionEventWithWorkerTokenReturns200() throws Exception {
        when(productExecutions.apply(anyString(), any())).thenReturn(execution());

        mockMvc.perform(post("/api/v5/internal/product-agent-executions/exec-1/events")
                        .header("X-Agent-Team-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productExecutionEvent()))
                .andExpect(status().isOk());
    }

    @Test
    void productExecutionStartWithoutWorkerTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v5/internal/product-agent-executions/exec-1/start"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productExecutionStartWithWorkerTokenReturns200() throws Exception {
        when(productExecutions.start("exec-1")).thenReturn(execution());

        mockMvc.perform(post("/api/v5/internal/product-agent-executions/exec-1/start")
                        .header("Authorization", "Bearer test-worker-token"))
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
                 "requirementManifestId":"manifest-1","phase":"planning",
                 "productArtifact":{"artifactId":"art-product-1","artifactType":"PRODUCT",
                   "version":1,"contentHash":"hash-product-1","rootArtifactId":"art-product-1",
                   "status":"APPROVED"},"gitBaseRevisions":{}}
                """;
    }

    private String productExecutionEvent() {
        return """
                {"event_id":"worker-event-1","event_type":"Heartbeat","stage":"drafting","attempt":1}
                """;
    }

    private ProductAgentExecution execution() {
        var now = Instant.now();
        return new ProductAgentExecution(
                "exec-1", "prd-1", ProductAgentExecutionStatus.RUNNING, "product-agent-exec-1",
                "msg-user", "bundle-1", "drafting", 1, null,
                now, null, now, null, now, now);
    }

    private ArtifactRef productRef() {
        return new ArtifactRef(
                "art-product-1", ArtifactType.PRODUCT, 1, "hash-product-1",
                "art-product-1", null, null, ArtifactStatus.APPROVED);
    }
}
