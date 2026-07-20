package com.asterism.workitem;

import com.asterism.attachment.Attachment;
import com.asterism.attachment.AttachmentRepository;
import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkItemControllerEventsTest {
    @Test
    void displayIdResolvesToInternalTimelineAndRemainsPublic() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");
        var event = event();
        when(workItems.findByDisplayWorkItemId("WI20260706001")).thenReturn(Optional.of(item()));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of(event));
        var actions = mock(WorkItemActionService.class);
        when(actions.availability(any(), eq(actor))).thenReturn(new WorkItemActionService.Availability(
                false, List.of(), null, "local", "auto"));
        var controller = new WorkItemController(workItems, events, actions, access,
                mock(com.asterism.prd.PrdSessionRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.asterism.git.GitIntegrationService.class), mock(com.asterism.git.GitLabClient.class),
                mock(AttachmentRepository.class));

        var detail = controller.detail("WI20260706001", actor);
        var timeline = controller.events("WI20260706001", actor);

        verify(access, times(2)).requireMember("sys-1", actor);
        assertThat(detail.workItemId()).isEqualTo("WI20260706001");
        assertThat(detail.canDelete()).isTrue();
        assertThat(timeline).containsExactly(event);
    }

    @Test
    void deletesActiveWorkItemWithoutChangingLifecycle() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");
        when(workItems.findByDisplayWorkItemId("WI20260706001")).thenReturn(Optional.of(item()));
        var controller = new WorkItemController(workItems, events, mock(WorkItemActionService.class), access,
                mock(com.asterism.prd.PrdSessionRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.asterism.git.GitIntegrationService.class), mock(com.asterism.git.GitLabClient.class),
                mock(AttachmentRepository.class));

        controller.delete("WI20260706001", actor);

        var deleted = org.mockito.ArgumentCaptor.forClass(WorkItemProjection.class);
        verify(workItems).save(deleted.capture());
        assertThat(deleted.getValue().deleted()).isTrue();
        assertThat(deleted.getValue().lifecycleStatus()).isEqualTo("activated");
        verify(access).requireMember("sys-1", actor);
        verify(access, never()).requireOwnerOrAdmin(anyString(), any());
    }

    @Test
    void listsWorkItemAttachmentsWithoutExposingStorageMetadata() {
        var workItems = mock(WorkItemProjectionRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var attachments = mock(AttachmentRepository.class);
        var actor = new UsernamePasswordAuthenticationToken("requester", "n/a");
        when(workItems.findByDisplayWorkItemId("WI20260706001")).thenReturn(Optional.of(item()));
        when(attachments.findByPrdIdAndSystemId("prd-1", "sys-1")).thenReturn(List.of(new Attachment(
                "att-1", "sys-1", "admin", "screen.png", "image/png", 128,
                "sha256-secret", "ab/sha256-secret", Instant.now())));
        var controller = new WorkItemController(workItems, events, mock(WorkItemActionService.class), access,
                mock(com.asterism.prd.PrdSessionRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.asterism.git.GitIntegrationService.class), mock(com.asterism.git.GitLabClient.class), attachments);

        var result = controller.attachments("WI20260706001", actor);

        assertThat(result).containsExactly(new WorkItemController.WorkItemAttachmentView(
                "att-1", "screen.png", "image/png", 128));
        verify(access).requireMember("sys-1", actor);
    }

    private WorkItemProjection item() {
        var now = Instant.now();
        return new WorkItemProjection("wi-1", "WI20260706001", "sys-1", "prd-1", "case-1", "任务",
                "activated", "approved", true, "Worker 已激活", "worker", "owner",
                false, 2, now, null, "requester", now, now);
    }

    private DomainEventRecord event() {
        return new DomainEventRecord(2L, "evt-2", "WorkItemActivated", "v5.0", "sys-1",
                "case-1", "prd-1", "wi-1", "worker", "worker", "{}",
                "case-1", "sig-1", "key-1", Instant.now());
    }
}
