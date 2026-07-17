package com.asterism.temporal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("temporal")
public class TemporalJavaSdkCaseAdapter implements TemporalCasePort {
    private static final Logger log = LoggerFactory.getLogger(TemporalJavaSdkCaseAdapter.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };
    private final WorkflowClient client;
    private final TemporalSettings settings;
    private final ObjectMapper objectMapper;

    // 显式选择生产构造器，测试构造器只负责注入 fake client。
    @Autowired
    public TemporalJavaSdkCaseAdapter(TemporalSettings settings) {
        this(newClient(settings), settings, new ObjectMapper());
    }

    TemporalJavaSdkCaseAdapter(WorkflowClient client, TemporalSettings settings, ObjectMapper objectMapper) {
        this.client = client;
        this.settings = settings;
        this.objectMapper = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private static WorkflowClient newClient(TemporalSettings settings) {
        var stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(settings.target()).build());
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(settings.namespace()).build());
    }

    @Override
    public String startCase(StartCaseCommand command) {
        var options = WorkflowOptions.newBuilder()
                .setWorkflowId(command.caseId())
                .setTaskQueue(settings.taskQueue())
                .build();
        var workflow = client.newUntypedWorkflowStub("AgentTeamV5CaseWorkflow", options);
        workflow.start(payload(command));
        log.info("Temporal workflow 已启动 caseId={}", command.caseId());
        return command.caseId();
    }

    @Override
    public void signalCase(SignalCaseCommand command) {
        Object payload = command.signalId();
        if (!command.context().isEmpty()) {
            var values = new LinkedHashMap<String, Object>();
            values.put("signal_id", command.signalId());
            values.putAll(command.context());
            payload = values;
        }
        client.newUntypedWorkflowStub(command.caseId()).signal(command.signalName(), payload);
        log.info("Temporal signal 已提交 caseId={} signal={}", command.caseId(), command.signalName());
    }

    @Override
    public String startRouteIndex(RouteIndexCommand command) {
        var workflowId = "route-index-" + command.systemId() + "-" + UUID.randomUUID();
        var options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(settings.taskQueue())
                .build();
        var workflow = client.newUntypedWorkflowStub("AsterismRouteIndexWorkflow", options);
        var input = new LinkedHashMap<String, Object>();
        input.put("system_id", command.systemId());
        input.put("repo_path", command.repoPath());
        input.put("repos", list(command.repos()));
        workflow.start(input);
        log.info("Temporal 路由索引已启动 system={} workflowId={}", command.systemId(), workflowId);
        return workflowId;
    }

    private Map<String, Object> payload(StartCaseCommand command) {
        var prd = command.prd() == null ? new PrdPayload(null, null, null, null) : command.prd();
        // Python workflow 使用 snake_case CaseInput，Jackson 负责字段命名和 null 容忍。
        return objectMapper.convertValue(new CasePayload(
                command.caseId(),
                command.workItemId(),
                command.prdId(),
                command.systemId(),
                text(command.repoPath()),
                list(command.allowedPaths()),
                list(command.forbiddenPaths()),
                list(command.testCommands()),
                list(command.repos()),
                text(command.releaseMode()),
                text(command.validationMode()),
                text(command.mrTargetBranch()),
                list(command.mrLabels()),
                command.agentConfigSnapshot(),
                new PrdPayloadDto(
                        text(prd.title()),
                        text(prd.goal()),
                        list(prd.acceptanceCriteria()),
                        map(prd.draftJson()))), PAYLOAD_TYPE);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private record CasePayload(
            String caseId,
            String workItemId,
            String prdId,
            String systemId,
            String repoPath,
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> testCommands,
            List<RepoSnapshot> repos,
            String releaseMode,
            String validationMode,
            String mrTargetBranch,
            List<String> mrLabels,
            AgentConfigSnapshot agentConfigSnapshot,
            PrdPayloadDto prd) {
    }

    private record PrdPayloadDto(String title, String goal, List<String> acceptanceCriteria, Map<String, Object> draftJson) {
    }
}
