package com.asterism.prd;

import com.asterism.temporal.TemporalSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalProductAgentExecutionAdapterTest {
    @Test
    void startsProductWorkflowWithDeterministicIdAndSnakeCasePayload() {
        var client = mock(WorkflowClient.class);
        var workflow = mock(WorkflowStub.class);
        var options = forClass(WorkflowOptions.class);
        when(client.newUntypedWorkflowStub(eq("AsterismProductAgentWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(workflow);
        var adapter = new TemporalProductAgentExecutionAdapter(
                client, new TemporalSettings("unused", "default", "product-queue"), new ObjectMapper());
        var command = new ProductAgentExecutionPort.StartExecutionCommand(
                "exec-1", "product-agent-exec-1", "sys-1", "prd-1", "conv-1", "msg-1", "bundle-1",
                "补充需求", List.of("att-1"), new ProductAgentPort.PrdContent(null, null, null, List.of()),
                List.of("title"), List.of(), List.of(), 1);

        adapter.start(command);

        verify(client).newUntypedWorkflowStub(eq("AsterismProductAgentWorkflow"), options.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo("product-agent-exec-1");
        assertThat(options.getValue().getTaskQueue()).isEqualTo("product-queue");
        @SuppressWarnings("unchecked")
        var payload = forClass(Map.class);
        verify(workflow).start(payload.capture());
        @SuppressWarnings("unchecked")
        var value = (Map<String, Object>) payload.getValue();
        assertThat(value)
                .containsEntry("execution_id", "exec-1")
                .containsEntry("workflow_id", "product-agent-exec-1")
                .containsEntry("input_message_id", "msg-1")
                .containsEntry("context_bundle_id", "bundle-1")
                .doesNotContainKeys("executionId", "workflowId", "inputMessageId", "contextBundleId");
    }
}
