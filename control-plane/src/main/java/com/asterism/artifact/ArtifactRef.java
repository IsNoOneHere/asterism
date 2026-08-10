package com.asterism.artifact;

/**
 * 跨进程只传递精确 Artifact 引用，禁止用 WorkItem 或最大版本猜测当前产物。
 */
public record ArtifactRef(
        String artifactId,
        ArtifactType artifactType,
        int version,
        String contentHash,
        String rootArtifactId,
        String parentArtifactId,
        String supersedesArtifactId,
        ArtifactStatus status) {

    public static ArtifactRef from(Artifact artifact) {
        return new ArtifactRef(
                artifact.artifactId(), artifact.artifactType(), artifact.version(), artifact.contentHash(),
                artifact.rootArtifactId(), artifact.parentArtifactId(), artifact.supersedesArtifactId(),
                artifact.status());
    }
}
