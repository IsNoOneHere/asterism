package com.asterism.prd;

import com.asterism.temporal.TemporalSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("temporal")
public class TemporalProductAgentExecutionAdapter implements ProductAgentExecutionPort {
    private static final Logger log = LoggerFactory.getLogger(TemporalProductAgentExecutionAdapter.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final WorkflowClient client;
    private final TemporalSettings settings;
    private final ObjectMapper objectMapper;

    @Autowired
    public TemporalProductAgentExecutionAdapter(TemporalSettings settings, ObjectMapper objectMapper) {
        this(newClient(settings), settings, objectMapper);
    }

    TemporalProductAgentExecutionAdapter(WorkflowClient client, TemporalSettings settings, ObjectMapper objectMapper) {
        this.client = client;
        this.settings = settings;
        this.objectMapper = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public String start(StartExecutionCommand command) {
        var options = WorkflowOptions.newBuilder()
                .setWorkflowId(command.workflowId())
                .setTaskQueue(settings.taskQueue())
                .build();
        var workflow = client.newUntypedWorkflowStub("AsterismProductAgentWorkflow", options);
        workflow.start(objectMapper.convertValue(command, PAYLOAD_TYPE));
        log.info("Temporal Product Agent workflow 已启动 executionId={} workflowId={}",
                command.executionId(), command.workflowId());
        return command.workflowId();
    }

    private static WorkflowClient newClient(TemporalSettings settings) {
        var stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(settings.target()).build());
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(settings.namespace()).build());
    }
}
