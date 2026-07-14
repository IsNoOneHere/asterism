package com.asterism.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalJavaSdkCaseAdapterTest {
    @Test
    void startCaseToleratesNullPrdPayloadFields() {
        var client = mock(WorkflowClient.class);
        var workflow = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(eq("AgentTeamV5CaseWorkflow"), any(WorkflowOptions.class))).thenReturn(workflow);
        var adapter = new TemporalJavaSdkCaseAdapter(client, new TemporalSettings("unused", "default", "queue"), new ObjectMapper());

        assertThatCode(() -> adapter.startCase(new TemporalCasePort.StartCaseCommand(
                "case-1",
                "wi-1",
                "prd-1",
                "system-1",
                "/tmp/repo",
                List.of("src"),
                List.of("secrets"),
                List.of("pytest"),
                "claude_sdk",
                40,
                900,
                new TemporalCasePort.PrdPayload(null, null, null, null))))
                .doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        var captor = forClass(Map.class);
        verify(workflow).start(captor.capture());
        @SuppressWarnings("unchecked")
        var payload = (Map<String, Object>) captor.getValue();
        @SuppressWarnings("unchecked")
        var prd = (Map<String, Object>) payload.get("prd");
        assertThat(prd.get("title")).isEqualTo("");
        assertThat(prd.get("goal")).isEqualTo("");
        assertThat(prd.get("acceptance_criteria")).isEqualTo(List.of());
        assertThat(prd.get("draft_json")).isEqualTo(Map.of());
        assertThat(payload.get("execution_provider")).isEqualTo("claude_sdk");
        assertThat(payload.get("claude_max_turns")).isEqualTo(40);
        assertThat(payload.get("execution_timeout_seconds")).isEqualTo(900);
    }
}
