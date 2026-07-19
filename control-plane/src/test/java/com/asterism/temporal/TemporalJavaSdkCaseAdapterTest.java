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
    void signalCaseAlwaysUsesObjectPayload() {
        var client = mock(WorkflowClient.class);
        var workflow = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub("case-1")).thenReturn(workflow);
        var adapter = new TemporalJavaSdkCaseAdapter(
                client, new TemporalSettings("unused", "default", "queue"), new ObjectMapper());

        adapter.signalCase(new TemporalCasePort.SignalCaseCommand(
                "case-1", "patch_apply_approved", "signal-1", Map.of()));

        verify(workflow).signal("patch_apply_approved", Map.of("signal_id", "signal-1"));
    }

    @Test
    void startCaseToleratesNullPrdPayloadFields() {
        var client = mock(WorkflowClient.class);
        var workflow = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(eq("AsterismCaseWorkflow"), any(WorkflowOptions.class))).thenReturn(workflow);
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
                new TemporalCasePort.AgentConfigSnapshot(
                        List.of(new TemporalCasePort.ModelProfileSnapshot(
                                "mp-1", "Claude", "anthropic", "https://example.invalid", "claude", false)),
                        List.of(new TemporalCasePort.AgentSnapshot(
                                "developer", "builtin", "claude_sdk_team", "mp-1", List.of("src"), "", 40, 900))),
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
        assertThat(payload).doesNotContainKeys("execution_provider", "claude_max_turns", "execution_timeout_seconds");
        assertThat(payload).containsEntry("execution_architecture", "claude_sdk_team");
        assertThat(payload).containsEntry("max_revisions", 5);
        @SuppressWarnings("unchecked")
        var snapshot = (Map<String, Object>) payload.get("agent_config_snapshot");
        assertThat(snapshot.toString()).doesNotContainIgnoringCase("apiKey");
        @SuppressWarnings("unchecked")
        var agents = (List<Map<String, Object>>) snapshot.get("agents");
        assertThat(agents.getFirst()).containsEntry("model_profile_ref", "mp-1")
                .containsEntry("timeout_seconds", 900);
    }
}
