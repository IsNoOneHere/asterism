package com.asterism.temporal;

import java.util.List;
import java.util.Map;

public interface TemporalCasePort {
    String startCase(StartCaseCommand command);

    void signalCase(SignalCaseCommand command);

    String startRouteIndex(RouteIndexCommand command);

    record StartCaseCommand(
            String caseId,
            String workItemId,
            String prdId,
            String systemId,
            String repoPath,
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> testCommands,
            AgentConfigSnapshot agentConfigSnapshot,
            PrdPayload prd) {
    }

    record AgentConfigSnapshot(List<ModelProfileSnapshot> modelProfiles, List<AgentSnapshot> agents) {
    }

    record ModelProfileSnapshot(String id, String name, String provider, String baseUrl, String model,
                                boolean supportsVision) {
    }

    record AgentSnapshot(String name, String kind, String engine, String modelProfileRef, List<String> pathScope,
                         String prompt, Integer maxTurns, Integer timeoutSeconds) {
    }

    record PrdPayload(String title, String goal, List<String> acceptanceCriteria, Map<String, Object> draftJson) {
    }

    record SignalCaseCommand(String caseId, String signalName, String signalId) {
    }

    record RouteIndexCommand(String systemId, String repoPath) {
    }
}
