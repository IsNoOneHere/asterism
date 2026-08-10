package com.asterism.memory;

import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.artifact.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ArtifactMemoryLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactMemoryLifecycleService.class);

    private final ArtifactService artifacts;
    private final ArtifactMemoryExtractor extractor;
    private final MemoryCandidateService candidates;
    private final TaskExecutor taskExecutor;

    public ArtifactMemoryLifecycleService(
            ArtifactService artifacts,
            ArtifactMemoryExtractor extractor,
            MemoryCandidateService candidates,
            TaskExecutor taskExecutor) {
        this.artifacts = artifacts;
        this.extractor = extractor;
        this.candidates = candidates;
        this.taskExecutor = taskExecutor;
    }

    public void schedule(ArtifactTransitionService.Result result) {
        if (result == null || result.artifactRef() == null || result.event() == null) return;
        var task = (Runnable) () -> execute(result);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(task, result.artifactRef().artifactId());
                }
            });
            return;
        }
        submit(task, result.artifactRef().artifactId());
    }

    private void submit(Runnable task, String artifactId) {
        try {
            taskExecutor.execute(task);
        } catch (RuntimeException error) {
            log.warn("Memory Extractor 任务提交失败 artifact={} type={}",
                    artifactId, error.getClass().getSimpleName());
        }
    }

    private void execute(ArtifactTransitionService.Result result) {
        var artifactId = result.artifactRef().artifactId();
        try {
            var artifact = artifacts.require(artifactId);
            if (artifact.artifactType() == ArtifactType.CODING
                    && artifact.status() == ArtifactStatus.REJECTED) {
                candidates.outdateRejectedCodingCandidate(artifact.artifactId());
            }
            var inputs = extractor.extract(artifact, result.event(), result.evidence());
            var created = candidates.createAll(inputs);
            // 提取完成后再同步一次来源状态，覆盖异步任务期间 Artifact 已被新版本替代的情况。
            candidates.refreshArtifactStatuses(artifact.rootArtifactId());
            if (!inputs.isEmpty()) {
                log.info("Artifact Memory 提取完成 artifact={} event={} proposed={} created={}",
                        artifact.artifactId(), result.event().eventType(), inputs.size(), created.size());
            }
        } catch (RuntimeException error) {
            // Memory 沉淀不能反向改变已经提交的 Artifact 生命周期。
            log.warn("Artifact Memory 提取失败 artifact={} type={}",
                    artifactId, error.getClass().getSimpleName());
        }
    }
}
