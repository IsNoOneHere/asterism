package com.asterism.artifact;

import java.time.Instant;
import java.util.List;

/** 单次验证的不可变业务结果；PASSED/FAILED 不占用 Artifact 生命周期状态。 */
public record ValidationArtifactContent(
        String validationRunId,
        Mode mode,
        Result result,
        List<CommandResult> commands,
        String errorSummary,
        String manualEvidence,
        String codingContentHash,
        Instant completedAt) implements ArtifactContent {

    public enum Mode {
        AUTO,
        MANUAL,
        SKIP
    }

    public enum Result {
        PASSED,
        FAILED,
        SKIPPED,
        ERROR
    }

    public record CommandResult(String repo, String command, Integer exitCode) {
    }
}
