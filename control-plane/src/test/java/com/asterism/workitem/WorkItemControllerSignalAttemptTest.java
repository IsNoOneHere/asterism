package com.asterism.workitem;

import com.asterism.common.ApiException;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemControllerSignalAttemptTest {
    @Test
    void sameRequestIsDispatchedOnlyOnce() {
        var fixture = fixture(item("validation_failed", 10));
        var key = "manual-action:wi-1:rework:request-001";
        when(fixture.events.exists(key)).thenReturn(false, true);
        var request = new WorkItemActionService.ActionRequest(
                "request-001", "validation_failed", 10L, "修复后重试", null);

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
                "request-001", "validation_failed", 9L, null, null);

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
                "request-001", "validation_failed", 11L, null, null);

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
                "request-002", "validation_failed", 12L, null, null);

        var response = fixture.service.submit("wi-1", "rework", request, fixture.actor);

        assertThat(response.signalId()).isEqualTo("rework-request-002");
        verify(fixture.temporal).signalCase(argThat(command -> "rework-request-002".equals(command.signalId())));
        verify(fixture.events).append(argThat(command ->
                "manual-action:wi-1:rework:request-002:retry:2".equals(command.idempotencyKey())
                        && Integer.valueOf(2).equals(command.payload().get("attempt"))));
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

    private Fixture fixture(WorkItemProjection item) {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item));
        when(workItems.lockById("wi-1")).thenReturn(Optional.of(item));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of());
        var service = new WorkItemActionService(workItems, temporal, events, access,
                new ObjectMapper(), directTransactions());
        return new Fixture(service, temporal, events, access,
                new UsernamePasswordAuthenticationToken("requester", "n/a"));
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

    private record Fixture(WorkItemActionService service, TemporalCasePort temporal, DomainEventService events,
                           SystemAccessService access, UsernamePasswordAuthenticationToken actor) {
    }
}
