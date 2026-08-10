package com.asterism.artifact;

import com.asterism.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ArtifactActionPolicy {
    private static final Set<String> SELECTED_CODING_ACTIONS = Set.of(
            "patch_apply_approved", "patch_apply_rejected",
            "validation_passed", "validation_rejected");
    private static final Set<String> PASSED_VALIDATION_ACTIONS = Set.of(
            "release_retry", "release_revalidate", "release_rework_coding");
    private static final Set<String> VALIDATION_REWORK_ACTIONS = Set.of(
            "validation_rework_coding", "validation_rework_planning");
    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("coding_plan_approved", new Rule(ArtifactType.PLANNING, ArtifactStatus.PROPOSED, false)),
            Map.entry("coding_plan_rejected", new Rule(ArtifactType.PLANNING, ArtifactStatus.PROPOSED, false)),
            Map.entry("patch_apply_approved", new Rule(ArtifactType.CODING, ArtifactStatus.PROPOSED, false)),
            Map.entry("patch_apply_rejected", new Rule(ArtifactType.CODING, ArtifactStatus.PROPOSED, false)),
            Map.entry("validation_passed", new Rule(ArtifactType.CODING, ArtifactStatus.PROPOSED, false)),
            Map.entry("validation_rejected", new Rule(ArtifactType.CODING, ArtifactStatus.PROPOSED, false)),
            Map.entry("validation_retry", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("validation_rework_coding", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("validation_rework_planning", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("release_retry", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("release_revalidate", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("release_rework_coding", new Rule(ArtifactType.VALIDATION, ArtifactStatus.APPROVED, true)),
            Map.entry("rework_with_latest_context", new Rule(ArtifactType.PRODUCT, ArtifactStatus.APPROVED, true)));

    private final ArtifactService artifacts;

    public ArtifactActionPolicy(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    public ArtifactRef validate(String action, String workItemId, ArtifactRef reference,
                                Map<ArtifactType, ArtifactRef> currentTransitionRefs) {
        if ("release_approved".equals(action)) {
            return validateReleaseApproval(workItemId, reference);
        }
        if (VALIDATION_REWORK_ACTIONS.contains(action)) {
            return validateValidationRework(workItemId, reference);
        }
        var rule = RULES.get(action);
        if (rule == null) return null;
        if (reference == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_REF_REQUIRED",
                    "请刷新页面，并对当前展示的产物版本执行操作");
        }
        // Coding 版本切换会把所选版本提升为有效 Head，后续确认必须继续绑定这个精确版本。
        if (SELECTED_CODING_ACTIONS.contains(action)
                && reference.status() == ArtifactStatus.APPROVED) {
            return validateEffectiveSelectedCoding(workItemId, rule, reference);
        }
        // Proposal 审核必须命中 Workflow 最后提交的 Transition，旧页面不能只因状态仍是 PROPOSED 而通过。
        if (!rule.effective() && !matchesCurrentProposal(
                workItemId, rule.type(), reference, currentTransitionRefs.get(rule.type()))) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "产物版本已变化，请刷新后重试");
        }
        final Artifact artifact;
        try {
            artifact = PASSED_VALIDATION_ACTIONS.contains(action)
                    ? artifacts.requirePassedValidation(reference)
                    : rule.effective()
                    ? artifacts.requireEffectiveApproved(reference)
                    : artifacts.requireEligibleProposal(reference);
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "产物版本已变化，请刷新后重试");
        }
        if (!artifact.workItemId().equals(workItemId)
                || artifact.artifactType() != rule.type()
                || artifact.status() != rule.status()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前动作绑定的产物版本或状态已变化，请刷新后重试");
        }
        return ArtifactRef.from(artifact);
    }

    /** 新 Case 绑定已通过的 ValidationArtifact，旧在途 Case 仍绑定当前 CodingArtifact。 */
    private ArtifactRef validateReleaseApproval(String workItemId, ArtifactRef reference) {
        if (reference == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_REF_REQUIRED",
                    "请刷新页面，并对当前展示的产物版本执行操作");
        }
        final Artifact artifact;
        try {
            var validation = artifacts.effectiveHeads(reference.rootArtifactId()).get(ArtifactType.VALIDATION);
            artifact = validation == null
                    ? artifacts.requireEffectiveApproved(reference)
                    : artifacts.requirePassedValidation(reference);
            var expectedType = validation == null ? ArtifactType.CODING : ArtifactType.VALIDATION;
            if (artifact.artifactType() != expectedType) {
                throw new ArtifactConflictException("发布动作绑定的 Artifact 类型不正确");
            }
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "产物版本已变化，请刷新后重试");
        }
        if (!artifact.workItemId().equals(workItemId)
                || artifact.status() != ArtifactStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前动作绑定的产物版本或状态已变化，请刷新后重试");
        }
        return ArtifactRef.from(artifact);
    }

    /** 新 Case 绑定 ValidationArtifact；V22 前在途 Case 没有验证产物时绑定当前 CodingArtifact。 */
    private ArtifactRef validateValidationRework(String workItemId, ArtifactRef reference) {
        if (reference == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ARTIFACT_REF_REQUIRED",
                    "请刷新页面，并对当前展示的产物版本执行操作");
        }
        final Artifact artifact;
        try {
            var validation = artifacts.effectiveHeads(reference.rootArtifactId()).get(ArtifactType.VALIDATION);
            artifact = artifacts.requireEffectiveApproved(reference);
            var expectedType = validation == null ? ArtifactType.CODING : ArtifactType.VALIDATION;
            if (artifact.artifactType() != expectedType) {
                throw new ArtifactConflictException("验证返工动作绑定的 Artifact 类型不正确");
            }
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "产物版本已变化，请刷新后重试");
        }
        if (!artifact.workItemId().equals(workItemId)
                || artifact.status() != ArtifactStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前动作绑定的产物版本或状态已变化，请刷新后重试");
        }
        return ArtifactRef.from(artifact);
    }

    private ArtifactRef validateEffectiveSelectedCoding(String workItemId, Rule rule, ArtifactRef reference) {
        final Artifact artifact;
        try {
            artifact = artifacts.requireEffectiveApproved(reference);
        } catch (ArtifactConflictException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "产物版本已变化，请刷新后重试");
        }
        if (!artifact.workItemId().equals(workItemId) || artifact.artifactType() != rule.type()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_ARTIFACT",
                    "当前动作绑定的产物版本或状态已变化，请刷新后重试");
        }
        return ArtifactRef.from(artifact);
    }

    private boolean matchesCurrentProposal(String workItemId, ArtifactType type,
                                           ArtifactRef reference, ArtifactRef transitionRef) {
        if (transitionRef != null) return reference.equals(transitionRef);
        // 兼容 ArtifactRef 上线前已产生的旧事件；只有唯一 Proposal 才能作为当前审核目标。
        List<Artifact> proposals = artifacts.findArtifactChain(workItemId).stream()
                .filter(artifact -> artifact.artifactType() == type
                        && artifact.status() == ArtifactStatus.PROPOSED)
                .toList();
        return proposals.size() == 1 && reference.equals(ArtifactRef.from(proposals.getFirst()));
    }

    private record Rule(ArtifactType type, ArtifactStatus status, boolean effective) {
    }
}
