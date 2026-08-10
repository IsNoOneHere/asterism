package com.asterism.artifact;

/** Artifact 内容只能使用已声明的类型化契约。 */
public sealed interface ArtifactContent permits ProductArtifactContent, PlanningArtifactContent,
        CodingArtifactContent, ValidationArtifactContent, ReleaseArtifactContent {
}
