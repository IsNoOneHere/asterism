package com.asterism.artifact;

/**
 * Evidence 类型由控制面统一约束，避免 Worker 用任意字符串伪造执行证据。
 */
public enum ArtifactEvidenceType {
    PlanningExecution,
    CodingExecution,
    WorkerBlocked,
    ReworkStarted,
    RevisionRequested,
    PatchApplied,
    PatchApplyBlocked,
    PatchRejected,
    ValidationPassed,
    ValidationFailed,
    RepositoryReleasePrepared,
    MergeRequestCreated,
    MergeRequestMerged,
    MergeRequestClosed,
    ReleaseCompleted,
    Commit,
    MergeRequest,
    Release
}
