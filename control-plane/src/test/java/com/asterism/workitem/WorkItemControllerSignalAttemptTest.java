package com.asterism.workitem;

import com.asterism.artifact.ArtifactActionPolicy;
import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactRef;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactStatus;
import com.asterism.artifact.ArtifactTransitionService;
import com.asterism.artifact.ArtifactType;
import com.asterism.common.ApiException;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemControllerSignalAttemptTest {
    @Test
    void selectedApprovedCodingVersionIsSentAsTheExactReviewRoute() {
        var fixture = fixture(item("modification_completed", 824));
        var product = new ArtifactRef(
                "art-product-1", ArtifactType.PRODUCT, 1, "hash-product-1",
                "art-product-1", null, null, ArtifactStatus.APPROVED);
        var planning = new ArtifactRef(
                "art-plan-2", ArtifactType.PLANNING, 2, "hash-plan-2",
                "art-product-1", "art-product-1", "art-plan-1", ArtifactStatus.APPROVED);
        var coding = new ArtifactRef(
                "art-code-1", ArtifactType.CODING, 1, "hash-code-1",
                "art-product-1", "art-plan-2", null, ArtifactStatus.APPROVED);
        when(fixture.artifactActions.validate(
                eq("patch_apply_approved"), eq("wi-1"), eq(coding), anyMap()))
                .thenReturn(coding);
        when(fixture.artifacts.effectiveHeads("art-product-1"))
                .thenReturn(Map.of(
                        ArtifactType.PRODUCT, product,
                        ArtifactType.PLANNING, planning,
                        ArtifactType.CODING, coding));
        var content = new ObjectMapper().createObjectNode().put("requirementManifestId", "manifest-1");
        when(fixture.artifacts.require("art-product-1")).thenReturn(new Artifact(
                "art-product-1", ArtifactType.PRODUCT, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                null, null, null, content, "hash-product-1", "product-1", "owner",
                Instant.now(), "owner", Instant.now(), "PRD 已确认"));
        var request = new WorkItemActionService.ActionRequest(
                "request-020", "modification_completed", 824L, null, null, coding);

        fixture.service.submit("wi-1", "patch_apply_approved", request, fixture.actor);

        verify(fixture.temporal).signalCase(argThat(command -> {
            var context = command.context();
            return "patch_apply_approved".equals(command.signalName())
                    && "CODING".equals(context.get("selected_type"))
                    && "manifest-1".equals(context.get("requirement_manifest_id"))
                    && "art-code-1".equals(((Map<?, ?>) context.get("artifact_ref")).get("artifact_id"))
                    && "art-code-1".equals(((Map<?, ?>) context.get("coding_artifact")).get("artifact_id"));
        }));
    }

    @Test
    void proposedCodingPlanReplacesStartWithApproveAndRequiredFeedbackReject() {
        var fixture = fixture(item("activated", 3));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(
                event(1, "WorkItemActivated", "{}"),
                event(2, "CodingPlanStarted", "{\"planRevision\":1}"),
                event(3, "CodingPlanProposed", "{\"planRevision\":1}")));

        var availability = fixture.service.availability(item("activated", 3), fixture.actor);

        assertThat(availability.actions()).containsExactly(
                "coding_plan_approved", "coding_plan_rejected", "cancel_case");
        assertThat(availability.currentStage()).isEqualTo("等待计划审批");
        assertThat(availability.waitingFor()).isEqualTo("owner");
        var blank = new WorkItemActionService.ActionRequest(
                "request-012", "activated", 3L, "  ", null, null);
        assertThatThrownBy(() -> fixture.service.submit(
                "wi-1", "coding_plan_rejected", blank, fixture.actor))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("ACTION_NOTE_REQUIRED");
    }

    @Test
    void patchAndMergeRejectionsRequireARevisionNote() {
        var patch = fixture(item("modification_completed", 10));
        var merge = fixture(item("waiting_merge", 11));
        var blankPatch = new WorkItemActionService.ActionRequest(
                "request-010", "modification_completed", 10L, "  ", null, null);
        var blankMerge = new WorkItemActionService.ActionRequest(
                "request-011", "waiting_merge", 11L, null, null, null);

        assertThatThrownBy(() -> patch.service.submit("wi-1", "patch_apply_rejected", blankPatch, patch.actor))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("ACTION_NOTE_REQUIRED");
        assertThatThrownBy(() -> merge.service.submit("wi-1", "rework", blankMerge, merge.actor))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("ACTION_NOTE_REQUIRED");
        verifyNoInteractions(patch.temporal, merge.temporal);
    }

    @Test
    void validationPassedReworkRequiresARevisionNote() {
        var fixture = fixture(item("validation_passed", 12));

        for (var action : List.of("validation_rework_coding", "validation_rework_planning")) {
            var request = new WorkItemActionService.ActionRequest(
                    "request-" + action, "validation_passed", 12L, "  ", null, null);
            assertThatThrownBy(() -> fixture.service.submit("wi-1", action, request, fixture.actor))
                    .isInstanceOf(ApiException.class)
                    .extracting(error -> ((ApiException) error).code())
                    .isEqualTo("ACTION_NOTE_REQUIRED");
        }
        verifyNoInteractions(fixture.temporal);
    }

    @Test
    void sameRequestIsDispatchedOnlyOnce() {
        var fixture = fixture(item("validation_failed", 10));
        var key = "manual-action:wi-1:rework:request-001";
        when(fixture.events.exists(key)).thenReturn(false, true);
        var request = new WorkItemActionService.ActionRequest(
                "request-001", "validation_failed", 10L, "修复后重试", null, null);

        var first = fixture.service.submit("wi-1", "rework", request, fixture.actor);
        var duplicate = fixture.service.submit("wi-1", "rework", request, fixture.actor);

        assertThat(first.signalId()).isEqualTo("rework-request-001");
        assertThat(duplicate.signalId()).isEqualTo(first.signalId());
        verify(fixture.temporal).signalCase(argThat(command ->
                "rework-request-001".equals(command.signalId())
                        && "修复后重试".equals(command.context().get("note"))));
        verify(fixture.events).append(any());
    }

    @Test
    void staleProjectionIsRejectedBeforeEventAndSignal() {
        var fixture = fixture(item("validation_failed", 10));
        var request = new WorkItemActionService.ActionRequest(
                "request-001", "validation_failed", 9L, null, null, null);

        assertThatThrownBy(() -> fixture.service.submit("wi-1", "rework", request, fixture.actor))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("STALE_WORK_ITEM");
        verify(fixture.events, never()).append(any());
        verifyNoInteractions(fixture.temporal);
    }

    @Test
    void anotherPendingActionBlocksOppositeSubmission() {
        var fixture = fixture(item("validation_failed", 11));
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                11, "TemporalSignalSubmitted",
                "{\"signalName\":\"rework\",\"signalId\":\"rework-request-000\",\"requestId\":\"request-000\"}")));
        var request = new WorkItemActionService.ActionRequest(
                "request-001", "validation_failed", 11L, null, null, null);

        assertThatThrownBy(() -> fixture.service.submit("wi-1", "cancel_case", request, fixture.actor))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("ACTION_PENDING");
        verify(fixture.events, never()).append(any());
        verifyNoInteractions(fixture.temporal);
    }

    @Test
    void failedTemporalSignalCanRetryWithSameSignalId() {
        var fixture = fixture(item("validation_failed", 12));
        var key = "manual-action:wi-1:rework:request-002";
        when(fixture.events.exists(key)).thenReturn(true);
        when(fixture.events.hasUnrecoveredSignalFailure("wi-1", "rework-request-002")).thenReturn(true);
        when(fixture.events.countSignalFailures("wi-1", "rework-request-002")).thenReturn(1L);
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(
                event(11, "TemporalSignalSubmitted",
                        "{\"signalName\":\"rework\",\"signalId\":\"rework-request-002\",\"requestId\":\"request-002\"}"),
                event(12, "TemporalSignalFailed", "{\"signalId\":\"rework-request-002\"}")));
        var request = new WorkItemActionService.ActionRequest(
                "request-002", "validation_failed", 12L, null, null, null);

        var response = fixture.service.submit("wi-1", "rework", request, fixture.actor);

        assertThat(response.signalId()).isEqualTo("rework-request-002");
        verify(fixture.temporal).signalCase(argThat(command -> "rework-request-002".equals(command.signalId())));
        verify(fixture.events).append(argThat(command ->
                "manual-action:wi-1:rework:request-002:retry:2".equals(command.idempotencyKey())
                        && Long.valueOf(2).equals(command.payload().get("attempt"))));
    }

    @Test
    void temporalFailureBecomesStaleConflictWhenProjectionAlreadyAdvanced() {
        var before = item("modification_completed", 10);
        var fixture = fixture(before);
        var completed = item("completed", 12);
        when(fixture.workItems.findById("wi-1")).thenReturn(Optional.of(before), Optional.of(completed));
        doThrow(new IllegalStateException("workflow already completed"))
                .when(fixture.temporal).signalCase(any());
        var request = new WorkItemActionService.ActionRequest(
                "request-005", "modification_completed", 10L, null, null, null);

        var error = catchThrowableOfType(() -> fixture.service.submit(
                "wi-1", "patch_apply_approved", request, fixture.actor), ApiException.class);

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo("STALE_WORK_ITEM");
        assertSignalFailureAudited(fixture, "patch_apply_approved-request-005");
    }

    @Test
    void temporalFailureRemainsRetryableWhenProjectionDidNotChange() {
        var current = item("validation_failed", 10);
        var fixture = fixture(current);
        doThrow(new IllegalStateException("temporal unavailable"))
                .when(fixture.temporal).signalCase(any());
        var request = new WorkItemActionService.ActionRequest(
                "request-006", "validation_failed", 10L, null, null, null);

        var error = catchThrowableOfType(() -> fixture.service.submit(
                "wi-1", "rework", request, fixture.actor), ApiException.class);

        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.code()).isEqualTo("TEMPORAL_SIGNAL_FAILED");
        assertSignalFailureAudited(fixture, "rework-request-006");
    }

    @Test
    void requesterCanOnlySubmitManualValidationActions() {
        var fixture = fixture(item("patch_applied", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(false);
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                1, "OwnerApprovalRequested", "{\"releaseMode\":\"gitlab\",\"validationMode\":\"manual\"}")));

        var validation = fixture.service.availability(item("patch_applied", 10), fixture.actor);
        var execution = fixture.service.availability(item("activated", 10), fixture.actor);

        assertThat(validation.canAct()).isTrue();
        assertThat(validation.actions()).containsExactly("validation_passed", "validation_rejected");
        assertThat(execution.canAct()).isFalse();
        assertThat(execution.actions()).isEmpty();
    }

    @Test
    void patchAppliedNeverOffersCancellation() {
        var fixture = fixture(item("patch_applied", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);

        assertThat(fixture.service.availability(item("patch_applied", 10), fixture.actor).actions())
                .containsExactly("validation_passed", "validation_rejected");
    }

    @Test
    void legacyValidationFailureKeepsReworkWhenNoValidationArtifactExists() {
        var fixture = fixture(item("validation_failed", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);

        assertThat(fixture.service.availability(item("validation_failed", 10), fixture.actor).actions())
                .containsExactly("rework", "cancel_case");
    }

    @Test
    void validationFailureUsesResultArtifactActionsWhenValidationHeadExists() {
        var fixture = fixture(item("validation_failed", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        var validation = new ArtifactRef(
                "art-validation-1", ArtifactType.VALIDATION, 1, "hash-validation-1",
                "art-product-1", "art-code-1", null, ArtifactStatus.APPROVED);
        var chain = new Artifact(
                "art-validation-1", ArtifactType.VALIDATION, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-code-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-validation-1", "validation-1", "worker", Instant.now(),
                "worker", Instant.now(), "验证结果 FAILED");
        when(fixture.artifacts.findArtifactChain("wi-1")).thenReturn(List.of(chain));
        when(fixture.artifacts.effectiveHeads("art-product-1"))
                .thenReturn(Map.of(ArtifactType.VALIDATION, validation));

        assertThat(fixture.service.availability(item("validation_failed", 10), fixture.actor).actions())
                .containsExactly(
                        "validation_retry", "validation_rework_coding",
                        "validation_rework_planning", "cancel_case");
    }

    @Test
    void validationPassedOffersReleaseAndTwoExplicitReworkActions() {
        var fixture = fixture(item("validation_passed", 12));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        var validationRef = new ArtifactRef(
                "art-validation-1", ArtifactType.VALIDATION, 1, "hash-validation-1",
                "art-product-1", "art-code-1", null, ArtifactStatus.APPROVED);
        var validation = new Artifact(
                "art-validation-1", ArtifactType.VALIDATION, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-code-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-validation-1", "validation-1", "worker", Instant.now(),
                "worker", Instant.now(), "验证结果 PASSED");
        when(fixture.artifacts.findArtifactChain("wi-1")).thenReturn(List.of(validation));
        when(fixture.artifacts.effectiveHeads("art-product-1"))
                .thenReturn(Map.of(ArtifactType.VALIDATION, validationRef));
        when(fixture.artifacts.requirePassedValidation(validationRef)).thenReturn(validation);

        assertThat(fixture.service.availability(item("validation_passed", 12), fixture.actor).actions())
                .containsExactly(
                        "release_approved", "validation_rework_coding",
                        "validation_rework_planning", "cancel_case");
    }

    @Test
    void preV22ValidationPassedUsesCodingHeadForReleaseAndRework() {
        var fixture = fixture(item("validation_passed", 12));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        var codingRef = new ArtifactRef(
                "art-code-1", ArtifactType.CODING, 1, "hash-code-1",
                "art-product-1", "art-plan-1", null, ArtifactStatus.APPROVED);
        var coding = new Artifact(
                "art-code-1", ArtifactType.CODING, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-plan-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-code-1", "coding-1", "worker", Instant.now(),
                "worker", Instant.now(), "Patch 已应用");
        when(fixture.artifacts.findArtifactChain("wi-1")).thenReturn(List.of(coding));
        when(fixture.artifacts.effectiveHeads("art-product-1"))
                .thenReturn(Map.of(ArtifactType.CODING, codingRef));

        assertThat(fixture.service.availability(item("validation_passed", 12), fixture.actor).actions())
                .containsExactly(
                        "release_approved", "validation_rework_coding",
                        "validation_rework_planning", "cancel_case");
    }

    @Test
    void latestConfigurationReworkSendsFreshSnapshotWithoutApiKey() {
        var fixture = fixture(item("worker_blocked", 10));
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                10, "WorkerBlocked", "{\"reason\":\"coding_attempt_failed\",\"failedPhase\":\"coding\"}")));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        when(fixture.configurations.internal("sys-1")).thenReturn(
                new AgentConfigurationService.InternalAgentConfiguration(
                        List.of(new AgentConfigurationService.ModelProfile(
                                "mp-latest", "deepseek-worker", "anthropic", "https://api.deepseek.com/anthropic",
                                "secret", "deepseek-v4-pro", false)),
                        List.of(new AgentConfigurationService.Agent(
                                "developer", "builtin", "claude_sdk_team", "mp-latest", List.of(), "", 50, 600)), 5));
        var request = new WorkItemActionService.ActionRequest(
                "request-003", "worker_blocked", 10L, "刷新配置", null, null);

        assertThat(fixture.service.availability(item("worker_blocked", 10), fixture.actor).actions())
                .containsExactly("retry_current_phase", "rework", "rework_with_latest_config", "cancel_case");
        fixture.service.submit("wi-1", "rework_with_latest_config", request, fixture.actor);

        var captor = ArgumentCaptor.forClass(TemporalCasePort.SignalCaseCommand.class);
        verify(fixture.temporal).signalCase(captor.capture());
        var command = captor.getValue();
        assertThat(command.signalName()).isEqualTo("rework_with_latest_config");
        assertThat(command.context().get("resume_failed_stage")).isEqualTo(true);
        assertThat(command.context().get("agent_config_snapshot").toString())
                .contains("model_profiles", "mp-latest", "claude_sdk_team")
                .doesNotContain("secret", "api_key");
    }

    @Test
    void workerBlockedActionsFollowFailedPhase() {
        var coding = fixture(item("worker_blocked", 10));
        when(coding.access.canControl("sys-1", coding.actor)).thenReturn(true);
        when(coding.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                10, "WorkerBlocked", "{\"reason\":\"coding_attempt_failed\",\"failedPhase\":\"coding\"}")));

        var patch = fixture(item("worker_blocked", 11));
        when(patch.access.canControl("sys-1", patch.actor)).thenReturn(true);
        when(patch.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                11, "PatchApplyBlocked", "{\"reason\":\"error: corrupt patch at line 120\",\"repo\":\"backend\"}")));

        assertThat(coding.service.availability(item("worker_blocked", 10), coding.actor).actions())
                .containsExactly("retry_current_phase", "rework", "rework_with_latest_config", "cancel_case");
        assertThat(patch.service.availability(item("worker_blocked", 11), patch.actor).actions())
                .containsExactly("retry_current_phase", "rework", "cancel_case");
    }

    @Test
    void staleContextOffersExplicitRefreshAndSendsNewManifest() {
        var fixture = fixture(item("worker_blocked", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                10, "WorkerBlocked", "{\"reason\":\"context_stale\",\"failedPhase\":\"planning\"}")));
        when(fixture.manifests.refresh("sys-1", "prd-1", "wi-1", "requester",
                "manual-action:wi-1:rework_with_latest_context:request-013"))
                .thenReturn("manifest-2");
        var currentProduct = new ArtifactRef(
                "art-product-1", ArtifactType.PRODUCT, 1, "hash-product-1",
                "art-product-1", null, null, ArtifactStatus.APPROVED);
        var refreshedProduct = new ArtifactRef(
                "art-product-2", ArtifactType.PRODUCT, 2, "hash-product-2",
                "art-product-1", null, "art-product-1", ArtifactStatus.APPROVED);
        when(fixture.artifactActions.validate(
                eq("rework_with_latest_context"), eq("wi-1"), eq(currentProduct), anyMap()))
                .thenReturn(currentProduct);
        when(fixture.artifactTransitions.refreshProductManifest(
                any(), eq(currentProduct), any(), any()))
                .thenReturn(new ArtifactTransitionService.Result(null, refreshedProduct, null, null));
        var request = new WorkItemActionService.ActionRequest(
                "request-013", "worker_blocked", 10L, "确认刷新", null, currentProduct);

        assertThat(fixture.service.availability(item("worker_blocked", 10), fixture.actor).actions())
                .contains("rework_with_latest_context");
        fixture.service.submit("wi-1", "rework_with_latest_context", request, fixture.actor);

        verify(fixture.temporal).signalCase(argThat(command ->
                "rework_with_latest_context".equals(command.signalName())
                        && "manifest-2".equals(command.context().get("requirement_manifest_id"))
                        && "art-product-2".equals(
                        ((java.util.Map<?, ?>) command.context().get("product_artifact")).get("artifact_id"))));
    }

    @Test
    void retryCurrentPhaseSendsDedicatedSignalWithoutRefreshingConfiguration() {
        var fixture = fixture(item("worker_blocked", 10));
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                10, "WorkerBlocked", "{\"reason\":\"release_failed\",\"failedPhase\":\"release\"}")));
        var request = new WorkItemActionService.ActionRequest(
                "request-004", "worker_blocked", 10L, null, null, null);

        fixture.service.submit("wi-1", "retry_current_phase", request, fixture.actor);

        verify(fixture.temporal).signalCase(argThat(command ->
                "retry_current_phase".equals(command.signalName())
                        && "retry_current_phase-request-004".equals(command.signalId())
                        && "release".equals(command.context().get("retry_phase"))
                        && !command.context().containsKey("agent_config_snapshot")));
        verifyNoInteractions(fixture.configurations);
    }

    @Test
    void resultArtifactReleaseFailureOnlyOffersVersionBoundRecoveryActions() {
        var fixture = fixture(item("worker_blocked", 10));
        when(fixture.access.canControl("sys-1", fixture.actor)).thenReturn(true);
        when(fixture.events.findByWorkItemId("wi-1")).thenReturn(List.of(event(
                10, "WorkerBlocked", "{\"reason\":\"release_failed\",\"failedPhase\":\"release\"}")));
        var validation = new ArtifactRef(
                "art-validation-1", ArtifactType.VALIDATION, 1, "hash-validation-1",
                "art-product-1", "art-code-1", null, ArtifactStatus.APPROVED);
        var chain = new Artifact(
                "art-validation-1", ArtifactType.VALIDATION, "art-product-1",
                "sys-1", "prd-1", "wi-1", "case-1", 1, ArtifactStatus.APPROVED,
                "art-code-1", null, null, new ObjectMapper().createObjectNode(),
                "hash-validation-1", "validation-1", "worker", Instant.now(),
                "worker", Instant.now(), "验证结果 PASSED");
        when(fixture.artifacts.findArtifactChain("wi-1")).thenReturn(List.of(chain));
        when(fixture.artifacts.effectiveHeads("art-product-1"))
                .thenReturn(Map.of(ArtifactType.VALIDATION, validation));

        assertThat(fixture.service.availability(item("worker_blocked", 10), fixture.actor).actions())
                .containsExactly(
                        "release_retry", "release_revalidate", "release_rework_coding", "cancel_case");
    }

    private Fixture fixture(WorkItemProjection item) {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var configurations = mock(AgentConfigurationService.class);
        var manifests = mock(RequirementContextManifestService.class);
        var artifacts = mock(ArtifactService.class);
        var artifactTransitions = mock(ArtifactTransitionService.class);
        var artifactActions = mock(ArtifactActionPolicy.class);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item));
        when(workItems.lockById("wi-1")).thenReturn(Optional.of(item));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of());
        var service = new WorkItemActionService(workItems, temporal, events, access,
                configurations, manifests,
                artifacts,
                artifactTransitions, artifactActions,
                new ObjectMapper(), directTransactions());
        return new Fixture(service, workItems, temporal, events, access, configurations, manifests,
                artifacts, artifactTransitions, artifactActions,
                new UsernamePasswordAuthenticationToken("requester", "n/a"));
    }

    private void assertSignalFailureAudited(Fixture fixture, String signalId) {
        var captor = ArgumentCaptor.forClass(DomainEventService.AppendEvent.class);
        verify(fixture.events, times(2)).append(captor.capture());
        assertThat(captor.getAllValues().get(1).eventType().name()).isEqualTo("TemporalSignalFailed");
        assertThat(captor.getAllValues().get(1).payload()).containsEntry("signalId", signalId);
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private WorkItemProjection item(String status, long sequence) {
        var now = Instant.now();
        return new WorkItemProjection(
                "wi-1", "WI20260706001", "sys-1", "prd-1", "case-1", "登录页错误提示",
                status, "approved", false, "等待处理", "owner", "owner", false, sequence,
                now, null, "requester", now, now);
    }

    private DomainEventRecord event(long sequence, String type, String payload) {
        return new DomainEventRecord(sequence, "evt-" + sequence, type, "v5.0", "sys-1", "case-1", "prd-1",
                "wi-1", "worker", "worker", payload, "wi-1", null, "key-" + sequence, Instant.now());
    }

    private record Fixture(WorkItemActionService service, WorkItemProjectionRepository workItems,
                           TemporalCasePort temporal, DomainEventService events,
                           SystemAccessService access, AgentConfigurationService configurations,
                           RequirementContextManifestService manifests,
                           ArtifactService artifacts,
                           ArtifactTransitionService artifactTransitions,
                           ArtifactActionPolicy artifactActions,
                           UsernamePasswordAuthenticationToken actor) {
    }
}
