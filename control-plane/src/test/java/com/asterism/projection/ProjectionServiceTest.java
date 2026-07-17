package com.asterism.projection;

import com.asterism.event.DomainEventRecord;
import com.asterism.prd.PrdSession;
import com.asterism.prd.PrdSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionServiceTest {
    @Test
    void firstProjectionUsesPrdTitle() {
        var store = new InMemoryStore();
        var sessions = mock(PrdSessionRepository.class);
        when(sessions.findById("prd-1")).thenReturn(Optional.of(new PrdSession(
                "prd-1", "sys-1", "conversation-1", null, null, "心跳接口", "新增心跳接口",
                "{}", "[]", "confirmed", "owner", null, null, Instant.now(), Instant.now())));

        new ProjectionService(store, sessions).apply(event(1, "OwnerApprovalRequested"));

        assertThat(store.findById("wi-1").orElseThrow().title()).isEqualTo("心跳接口");
    }

    @Test
    void ownerApprovalSignalDoesNotActivateWorkItem() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "OwnerApprovalSignalSubmitted"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(item.executionAllowed()).isFalse();
        assertThat(item.approvalStatus()).isEqualTo("submitted");
    }

    @Test
    void olderSequenceCannotDowngradeProjection() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(10, "OwnerApprovalRequested"));
        service.apply(event(11, "WorkItemActivated"));
        service.apply(event(9, "OwnerApprovalSignalSubmitted"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("activated");
        assertThat(item.executionAllowed()).isTrue();
        assertThat(item.lastAppliedSequence()).isEqualTo(11);
    }

    @Test
    void laterApprovalSignalCannotDowngradeActivatedWorkItem() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "OwnerApprovalSignalSubmitted"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("activated");
        assertThat(item.executionAllowed()).isTrue();
        assertThat(item.lastAppliedSequence()).isEqualTo(2);
    }

    @Test
    void ownerRejectionProjectsToRejectedTerminalState() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemRejected"));
        service.apply(event(3, "WorkItemActivated"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("rejected");
        assertThat(item.executionAllowed()).isFalse();
        assertThat(item.lastAppliedSequence()).isEqualTo(2);
    }

    @Test
    void patchApplyBlockedProjectsBackToWorkerBlocked() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchApplyBlocked"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("worker_blocked");
        assertThat(item.executionAllowed()).isFalse();
        assertThat(item.lastAppliedSequence()).isEqualTo(4);
    }

    @Test
    void releaseFailureProjectsBackToWorkerBlockedAfterValidationPassed() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchApplied"));
        service.apply(event(5, "ValidationPassed"));
        service.apply(event(6, "WorkerBlocked"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("worker_blocked");
        assertThat(item.executionAllowed()).isFalse();
        assertThat(item.lastAppliedSequence()).isEqualTo(6);
    }

    @Test
    void gitlabMergeRequestsProjectThroughWaitingMergeToCompleted() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchApplied"));
        service.apply(event(5, "ValidationPassed"));
        service.apply(event(6, "MergeRequestCreated"));
        service.apply(event(7, "MergeRequestMerged"));

        assertThat(store.findById("wi-1").orElseThrow().lifecycleStatus()).isEqualTo("waiting_merge");
        service.apply(event(8, "ReleaseCompleted"));
        assertThat(store.findById("wi-1").orElseThrow().lifecycleStatus()).isEqualTo("completed");
    }

    @Test
    void closedMergeRequestProjectsToWorkerBlocked() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchApplied"));
        service.apply(event(5, "ValidationPassed"));
        service.apply(event(6, "MergeRequestCreated"));
        service.apply(event(7, "MergeRequestClosed"));

        assertThat(store.findById("wi-1").orElseThrow().lifecycleStatus()).isEqualTo("worker_blocked");
    }

    @Test
    void executionPlanDraftedDoesNotChangeLifecycleProjection() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ExecutionPlanDrafted"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("activated");
        assertThat(item.lastAppliedSequence()).isEqualTo(2);
    }

    @Test
    void reworkStartedReturnsToActivatedWithoutResettingApproval() {
        var store = new InMemoryStore();
        var service = service(store);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchRejected"));
        service.apply(event(5, "ReworkStarted"));

        var item = store.findById("wi-1").orElseThrow();
        assertThat(item.lifecycleStatus()).isEqualTo("activated");
        assertThat(item.approvalStatus()).isEqualTo("approved");
        assertThat(item.executionAllowed()).isTrue();
    }

    @Test
    void lifecycleStatusAlsoUpdatesPrdAfterProjectionSucceeds() {
        var store = new InMemoryStore();
        var sessions = mock(PrdSessionRepository.class);
        var service = new ProjectionService(store, sessions);

        service.apply(event(1, "OwnerApprovalRequested"));
        service.apply(event(2, "WorkItemActivated"));
        service.apply(event(3, "ModificationCompleted"));
        service.apply(event(4, "PatchApplied"));
        service.apply(event(5, "ValidationPassed"));
        service.apply(event(6, "ReleaseCompleted"));
        service.apply(event(2, "WorkItemActivated"));

        verify(sessions).updateLifecycleStatus(eq("wi-1"), eq("completed"), any(Instant.class));
        verify(sessions, never()).updateLifecycleStatus(eq("wi-1"), eq("allocated"), any(Instant.class));
    }

    private ProjectionService service(InMemoryStore store) {
        return new ProjectionService(store, mock(PrdSessionRepository.class));
    }

    private DomainEventRecord event(long sequence, String type) {
        return new DomainEventRecord(
                sequence,
                "evt-" + sequence,
                type,
                "v5.0",
                "sys-1",
                "case-1",
                "prd-1",
                "wi-1",
                "owner",
                "test",
                "{}",
                "corr-1",
                null,
                "key-" + sequence,
                Instant.now());
    }

    private static final class InMemoryStore implements WorkItemProjectionStore {
        private final Map<String, WorkItemProjection> items = new HashMap<>();

        @Override
        public Optional<WorkItemProjection> findById(String workItemId) {
            return Optional.ofNullable(items.get(workItemId));
        }

        @Override
        public WorkItemProjection save(WorkItemProjection projection) {
            items.put(projection.workItemId(), projection);
            return projection;
        }
    }
}
