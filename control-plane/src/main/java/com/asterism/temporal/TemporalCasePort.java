package com.asterism.temporal;

import com.asterism.artifact.ArtifactRef;

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
            List<RepoSnapshot> repos,
            String releaseMode,
            String validationMode,
            String mrTargetBranch,
            List<String> mrLabels,
            int maxRevisions,
            AgentConfigSnapshot agentConfigSnapshot,
            PrdPayload prd) {
        public StartCaseCommand(String caseId, String workItemId, String prdId, String systemId, String repoPath,
                                List<String> allowedPaths, List<String> forbiddenPaths, List<String> testCommands,
                                AgentConfigSnapshot agentConfigSnapshot, PrdPayload prd) {
            this(caseId, workItemId, prdId, systemId, repoPath, allowedPaths, forbiddenPaths, testCommands,
                    List.of(), "local", "auto", "", List.of(), 5, agentConfigSnapshot, prd);
        }
    }

    record RepoSnapshot(String repoId, String name, String kind, String gitlabProject, String defaultBranch,
                        String cloneMode, String localPath, List<String> allowedPaths,
                        List<String> forbiddenPaths, List<String> testCommands) {
    }

    record AgentConfigSnapshot(List<ModelProfileSnapshot> modelProfiles, List<AgentSnapshot> agents) {
    }

    record ModelProfileSnapshot(String id, String name, String provider, String baseUrl, String model,
                                boolean supportsVision, boolean imageInput, String structuredOutput) {
        public ModelProfileSnapshot(String id, String name, String provider, String baseUrl, String model,
                                    boolean supportsVision) {
            this(id, name, provider, baseUrl, model, supportsVision, supportsVision, "json_object");
        }
    }

    record AgentSnapshot(String name, String kind, String engine, String modelProfileRef, List<String> pathScope,
                         String prompt, Integer maxTurns, Integer timeoutSeconds) {
    }

    record PrdPayload(String requirementManifestId, ArtifactRef productArtifact) {
    }

    record SignalCaseCommand(String caseId, String signalName, String signalId, Map<String, Object> context) {
        public SignalCaseCommand(String caseId, String signalName, String signalId) {
            this(caseId, signalName, signalId, Map.of());
        }
    }

    record RouteIndexCommand(String systemId, String repoPath, List<RepoSnapshot> repos) {
        public RouteIndexCommand(String systemId, String repoPath) {
            this(systemId, repoPath, List.of());
        }
    }
}
