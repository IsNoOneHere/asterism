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
            String executionProvider,
            Integer claudeMaxTurns,
            Integer executionTimeoutSeconds,
            PrdPayload prd) {
    }

    record PrdPayload(String title, String goal, List<String> acceptanceCriteria, Map<String, Object> draftJson) {
    }

    record SignalCaseCommand(String caseId, String signalName, String signalId) {
    }

    record RouteIndexCommand(String systemId, String repoPath) {
    }
}
