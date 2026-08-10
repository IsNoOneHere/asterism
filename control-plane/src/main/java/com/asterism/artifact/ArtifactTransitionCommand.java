package com.asterism.artifact;

/**
 * 状态规则只消费这些类型化命令；Controller、Workflow 和事件层不再反推 Artifact。
 */
public sealed interface ArtifactTransitionCommand permits
        ArtifactTransitionCommand.ProposePlanningArtifact,
        ArtifactTransitionCommand.ApprovePlanningArtifact,
        ArtifactTransitionCommand.RejectPlanningArtifact,
        ArtifactTransitionCommand.ProposeCodingArtifact,
        ArtifactTransitionCommand.ApproveCodingArtifact,
        ArtifactTransitionCommand.RejectCodingArtifact,
        ArtifactTransitionCommand.SupersedeArtifact,
        ArtifactTransitionCommand.AppendArtifactEvidence {

    String transitionId();

    String actor();

    record ProposePlanningArtifact(
            String transitionId,
            String actor,
            ArtifactRef parent,
            ArtifactRef supersedes,
            ArtifactRef expectedHead,
            PlanningArtifactContent content) implements ArtifactTransitionCommand {
    }

    record ApprovePlanningArtifact(
            String transitionId,
            String actor,
            ArtifactRef artifact,
            ArtifactRef expectedHead,
            String note) implements ArtifactTransitionCommand {
    }

    record RejectPlanningArtifact(
            String transitionId,
            String actor,
            ArtifactRef artifact,
            ArtifactRef expectedHead,
            String note) implements ArtifactTransitionCommand {
    }

    record ProposeCodingArtifact(
            String transitionId,
            String actor,
            ArtifactRef parent,
            ArtifactRef supersedes,
            ArtifactRef expectedHead,
            CodingArtifactContent content) implements ArtifactTransitionCommand {
    }

    record ApproveCodingArtifact(
            String transitionId,
            String actor,
            ArtifactRef artifact,
            ArtifactRef expectedHead,
            String note) implements ArtifactTransitionCommand {
    }

    record RejectCodingArtifact(
            String transitionId,
            String actor,
            ArtifactRef artifact,
            ArtifactRef expectedHead,
            String note) implements ArtifactTransitionCommand {
    }

    record SupersedeArtifact(
            String transitionId,
            String actor,
            ArtifactRef artifact,
            ArtifactRef expectedHead,
            String note) implements ArtifactTransitionCommand {
    }

    record AppendArtifactEvidence(
            String transitionId,
            String actor,
            String evidenceId,
            ArtifactRef artifact,
            ArtifactEvidenceType evidenceType,
            com.fasterxml.jackson.databind.JsonNode payload) implements ArtifactTransitionCommand {
    }
}
