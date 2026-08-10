package com.asterism.artifact;

import com.asterism.common.ApiException;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactVersionSelectionServiceTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ArtifactTransitionService transitions = mock(ArtifactTransitionService.class);
    private final DomainEventService events = mock(DomainEventService.class);
    private final TemporalCasePort temporal = mock(TemporalCasePort.class);
    private final WorkItemProjectionRepository workItems = mock(WorkItemProjectionRepository.class);
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
    private final ArtifactVersionSelectionService service = new ArtifactVersionSelectionService(
            artifacts, transitions, events, temporal, workItems, new ObjectMapper(), transactions);

    @BeforeEach
    void setUp() {
        when(workItems.lockById("wi-1")).thenReturn(Optional.of(workItem("activated")));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of());
    }

    @Test
    void selectingVersionOnlyUpdatesTheEffectiveRoute() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var approvedPlanning = artifact(
                "planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-select-1", ArtifactRef.from(planning), Map.of(ArtifactType.PRODUCT, ArtifactRef.from(product)));
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(approvedPlanning));
        when(artifacts.requireExact(ArtifactRef.from(planning))).thenReturn(planning);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(List.of(product, planning));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(
                Map.of(ArtifactType.PRODUCT, ArtifactRef.from(product)));
        when(transitions.selectVersion(any(), any(), any(), any())).thenReturn(
                new ArtifactTransitionService.VersionSelectionResult(
                        null, heads.get(ArtifactType.PLANNING), heads, List.of()));

        var response = service.select(item, request, "owner");

        assertThat(response.status()).isEqualTo("selected");
        assertThat(response.signalId()).isEmpty();
        assertThat(response.effectiveHeads()).isEqualTo(heads);
        verify(temporal, never()).signalCase(any());
        verify(events, never()).append(any());
    }

    @Test
    void continuingFromSelectedPlanningSignalsTheExistingRoute() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning));
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-continue-1", ArtifactRef.from(planning), heads);
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(List.of(product, planning));
        when(artifacts.requireExact(ArtifactRef.from(planning))).thenReturn(planning);
        when(artifacts.requireEffectiveApproved(ArtifactRef.from(planning))).thenReturn(planning);
        when(artifacts.require(product.artifactId())).thenReturn(product);
        when(events.exists(any())).thenReturn(false);
        when(events.countSignalFailures(item.workItemId(), "artifact-version-continue-request-continue-1"))
                .thenReturn(0L);

        var response = service.continueExecution(item, request, "owner");

        assertThat(response.status()).isEqualTo("submitted");
        var signal = ArgumentCaptor.forClass(TemporalCasePort.SignalCaseCommand.class);
        verify(temporal).signalCase(signal.capture());
        assertThat(signal.getValue().signalName()).isEqualTo("artifact_version_selected");
        assertThat(signal.getValue().context()).containsKeys(
                "product_artifact", "planning_artifact", "selected_artifact");
    }

    @Test
    void continuingWithTheSameRequestIdReusesTheSubmittedCommand() throws Exception {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning));
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-continue-replay", ArtifactRef.from(planning), heads);
        var submissionKey = "artifact-version-continue:wi-1:request-continue-replay";
        when(events.exists(submissionKey)).thenReturn(true);
        when(events.hasUnrecoveredSignalFailure(
                item.workItemId(), "artifact-version-continue-request-continue-replay")).thenReturn(false);
        when(events.findByWorkItemId(item.workItemId())).thenReturn(List.of(new DomainEventRecord(
                10L, "evt-10", "TemporalSignalSubmitted", "v5.0", "sys-1", "case-1", "prd-1", "wi-1",
                "owner", "control-plane", new ObjectMapper().writeValueAsString(Map.of(
                "artifactRef", ArtifactRef.from(planning),
                "expectedHeads", heads,
                "selectedType", "PLANNING",
                "selectedVersion", 1)), "case-1", null, submissionKey,
                Instant.parse("2026-07-31T00:00:00Z"))));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);

        var response = service.continueExecution(item, request, "owner");

        assertThat(response.status()).isEqualTo("submitted");
        assertThat(response.effectiveHeads()).isEqualTo(heads);
        verify(temporal, never()).signalCase(any());
        verify(events, never()).append(any());
    }

    @Test
    void planningCannotBeSelectedAfterCodingArtifactExists() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning1 = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var planning2 = artifact("planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product.artifactId());
        var coding = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.PROPOSED, planning2.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning2));
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-switch-old-plan", ArtifactRef.from(planning1), heads);
        when(artifacts.requireExact(ArtifactRef.from(planning1))).thenReturn(planning1);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(
                List.of(product, planning1, planning2, coding));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Coding 已开始");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    @Test
    void planningCannotBeSelectedAfterWorkflowEnteredCodingWithoutArtifact() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning1 = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var planning2 = artifact("planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning2));
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-switch-running", ArtifactRef.from(planning1), heads);
        when(artifacts.requireExact(ArtifactRef.from(planning1))).thenReturn(planning1);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(List.of(product, planning1, planning2));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        when(events.findByWorkItemId(item.workItemId())).thenReturn(List.of(event("CodingAttemptStarted", 10)));

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Coding 已开始");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    @Test
    void graphActionsRejectPlanningHistoryFromPreviousProductRoute() {
        var item = workItem("activated");
        var product1 = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.SUPERSEDED, null);
        var product2 = artifact("product-2", ArtifactType.PRODUCT, 2, ArtifactStatus.APPROVED, null);
        var oldPlanning = artifact(
                "planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product1.artifactId());
        var currentPlanning = artifact(
                "planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product2.artifactId());
        var safeHistory = artifact(
                "planning-3", ArtifactType.PLANNING, 3, ArtifactStatus.REJECTED, product2.artifactId());
        when(artifacts.effectiveHeads(product1.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product2),
                ArtifactType.PLANNING, ArtifactRef.from(currentPlanning)));

        var actions = service.versionActions(
                item, List.of(product1, product2, oldPlanning, currentPlanning, safeHistory), true);

        assertThat(actions.get(oldPlanning.artifactId()).canSelect()).isFalse();
        assertThat(actions.get(oldPlanning.artifactId()).selectDisabledReason()).contains("旧 Product");
        assertThat(actions.get(safeHistory.artifactId()).canSelect()).isTrue();
    }

    @Test
    void selectRejectsPlanningHistoryFromPreviousProductRoute() {
        var item = workItem("activated");
        var product1 = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.SUPERSEDED, null);
        var product2 = artifact("product-2", ArtifactType.PRODUCT, 2, ArtifactStatus.APPROVED, null);
        var oldPlanning = artifact(
                "planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product1.artifactId());
        var currentPlanning = artifact(
                "planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product2.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product2),
                ArtifactType.PLANNING, ArtifactRef.from(currentPlanning));
        when(artifacts.requireExact(ArtifactRef.from(oldPlanning))).thenReturn(oldPlanning);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(
                List.of(product1, product2, oldPlanning, currentPlanning));
        when(artifacts.effectiveHeads(product1.rootArtifactId())).thenReturn(heads);
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-old-product-plan", ArtifactRef.from(oldPlanning), heads);

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("ARTIFACT_VERSION_ROLLBACK_REQUIRED"))
                .hasMessageContaining("旧 Product");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    @Test
    void graphActionsRejectProposedPlanningVersion() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var currentPlanning = artifact(
                "planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var proposal = artifact(
                "planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.PROPOSED, product.artifactId());
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(currentPlanning)));

        var actions = service.versionActions(item, List.of(product, currentPlanning, proposal), true);

        assertThat(actions.get(proposal.artifactId()).canSelect()).isFalse();
        assertThat(actions.get(proposal.artifactId()).selectDisabledReason())
                .isEqualTo("待审核产物必须使用当前审批操作");
    }

    @Test
    void selectRejectsProposedPlanningVersion() {
        var item = workItem("activated");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var currentPlanning = artifact(
                "planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var proposal = artifact(
                "planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.PROPOSED, product.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(currentPlanning));
        when(artifacts.requireExact(ArtifactRef.from(proposal))).thenReturn(proposal);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(List.of(product, currentPlanning, proposal));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-proposed-plan", ArtifactRef.from(proposal), heads);

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("ARTIFACT_VERSION_SWITCH_NOT_AVAILABLE"))
                .hasMessageContaining("待审核产物必须使用当前审批操作");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    @Test
    void graphActionsAllowOnlyCodingHistoryAtCodeConfirmation() {
        var item = workItem("modification_completed");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning1 = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var planning2 = artifact("planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product.artifactId());
        var coding1 = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.REJECTED, planning2.artifactId());
        var coding2 = artifact("coding-2", ArtifactType.CODING, 2, ArtifactStatus.PROPOSED, planning2.artifactId());
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning2),
                ArtifactType.CODING, ArtifactRef.from(coding2)));

        var actions = service.versionActions(
                item, List.of(product, planning1, planning2, coding1, coding2), true);

        assertThat(actions.get(planning1.artifactId()).canSelect()).isFalse();
        assertThat(actions.get(planning1.artifactId()).selectDisabledReason()).contains("Coding 已开始");
        assertThat(actions.get(coding1.artifactId()).canSelect()).isTrue();
        assertThat(actions.get(coding2.artifactId()).canSelect()).isFalse();
    }

    @Test
    void graphActionsRejectCodingHistoryFromPreviousPlanningRoute() {
        var item = workItem("modification_completed");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning1 = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var planning2 = artifact("planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product.artifactId());
        var oldCoding = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.REJECTED, planning1.artifactId());
        var currentCoding = artifact(
                "coding-2", ArtifactType.CODING, 2, ArtifactStatus.PROPOSED, planning2.artifactId());
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning2),
                ArtifactType.CODING, ArtifactRef.from(currentCoding)));

        var actions = service.versionActions(
                item, List.of(product, planning1, planning2, oldCoding, currentCoding), true);

        assertThat(actions.get(oldCoding.artifactId()).canSelect()).isFalse();
        assertThat(actions.get(oldCoding.artifactId()).selectDisabledReason()).contains("旧 Planning");
    }

    @Test
    void selectRejectsCodingHistoryFromPreviousPlanningRoute() {
        var item = workItem("modification_completed");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning1 = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.REJECTED, product.artifactId());
        var planning2 = artifact("planning-2", ArtifactType.PLANNING, 2, ArtifactStatus.APPROVED, product.artifactId());
        var oldCoding = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.REJECTED, planning1.artifactId());
        var currentCoding = artifact(
                "coding-2", ArtifactType.CODING, 2, ArtifactStatus.PROPOSED, planning2.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning2),
                ArtifactType.CODING, ArtifactRef.from(currentCoding));
        when(workItems.lockById(item.workItemId())).thenReturn(Optional.of(item));
        when(artifacts.requireExact(ArtifactRef.from(oldCoding))).thenReturn(oldCoding);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(
                List.of(product, planning1, planning2, oldCoding, currentCoding));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-old-code-route", ArtifactRef.from(oldCoding), heads);

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.code()).isEqualTo("ARTIFACT_VERSION_ROLLBACK_REQUIRED"))
                .hasMessageContaining("旧 Planning");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    @Test
    void selectAllowsCodingHistoryFromCurrentPlanningAtCodeConfirmation() {
        var item = workItem("modification_completed");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var history = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.REJECTED, planning.artifactId());
        var currentCoding = artifact(
                "coding-2", ArtifactType.CODING, 2, ArtifactStatus.APPROVED, planning.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning),
                ArtifactType.CODING, ArtifactRef.from(currentCoding));
        when(workItems.lockById(item.workItemId())).thenReturn(Optional.of(item));
        when(artifacts.requireExact(ArtifactRef.from(history))).thenReturn(history);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(
                List.of(product, planning, history, currentCoding));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        when(transitions.selectVersion(any(), any(), any(), any())).thenReturn(
                new ArtifactTransitionService.VersionSelectionResult(null, ArtifactRef.from(history), heads, List.of()));
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-safe-code-route", ArtifactRef.from(history), heads);

        assertThat(service.select(item, request, "owner").status()).isEqualTo("selected");
        verify(transitions).selectVersion(any(), any(), any(), any());
    }

    @Test
    void codingHistoryCannotBeSelectedAfterPatchStage() {
        var item = workItem("patch_applied");
        var product = artifact("product-1", ArtifactType.PRODUCT, 1, ArtifactStatus.APPROVED, null);
        var planning = artifact("planning-1", ArtifactType.PLANNING, 1, ArtifactStatus.APPROVED, product.artifactId());
        var coding1 = artifact("coding-1", ArtifactType.CODING, 1, ArtifactStatus.REJECTED, planning.artifactId());
        var coding2 = artifact("coding-2", ArtifactType.CODING, 2, ArtifactStatus.APPROVED, planning.artifactId());
        var heads = Map.of(
                ArtifactType.PRODUCT, ArtifactRef.from(product),
                ArtifactType.PLANNING, ArtifactRef.from(planning),
                ArtifactType.CODING, ArtifactRef.from(coding2));
        when(workItems.lockById(item.workItemId())).thenReturn(Optional.of(item));
        when(artifacts.requireExact(ArtifactRef.from(coding1))).thenReturn(coding1);
        when(artifacts.findArtifactChain(item.workItemId())).thenReturn(List.of(product, planning, coding1, coding2));
        when(artifacts.effectiveHeads(product.rootArtifactId())).thenReturn(heads);
        var request = new ArtifactVersionSelectionService.SelectionRequest(
                "request-switch-code", ArtifactRef.from(coding1), heads);

        assertThatThrownBy(() -> service.select(item, request, "owner"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("代码确认阶段");
        verify(transitions, never()).selectVersion(any(), any(), any(), any());
    }

    private WorkItemProjection workItem(String lifecycleStatus) {
        var now = Instant.parse("2026-07-31T00:00:00Z");
        return new WorkItemProjection(
                "wi-1", "WI1", "sys-1", "prd-1", "case-1", "测试工作项",
                lifecycleStatus, "approved", true, "Worker 已激活", "owner", "owner",
                false, 1, now, null, "owner", now, now);
    }

    private DomainEventRecord event(String eventType, long sequence) {
        return new DomainEventRecord(
                sequence, "evt-" + sequence, eventType, "v5.0", "sys-1", "case-1", "prd-1", "wi-1",
                "worker", "worker", "{}", "case-1", null, "event-" + sequence,
                Instant.parse("2026-07-31T00:00:00Z"));
    }

    private Artifact artifact(String id, ArtifactType type, int version,
                              ArtifactStatus status, String parentId) {
        var content = type == ArtifactType.PRODUCT
                ? new ObjectMapper().valueToTree(Map.of("requirementManifestId", "manifest-1"))
                : new ObjectMapper().createObjectNode();
        return new Artifact(
                id, type, "product-1", "sys-1", "prd-1", "wi-1", "case-1",
                version, status, parentId, null, null, content, "hash-" + id,
                "transition-" + id, "worker", Instant.parse("2026-07-31T00:00:00Z"),
                null, null, null);
    }
}
