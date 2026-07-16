package com.asterism.memory;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryControllerSnapshotTest {
    @Test
    void candidateStoresStructuredMetadata() {
        var aggregate = mock(JdbcAggregateTemplate.class);
        var controller = new MemoryController(mock(MemoryItemRepository.class), mock(DomainEventService.class),
                mock(SystemAccessService.class), mock(ContextManifestService.class), aggregate, new ObjectMapper());
        var actor = new UsernamePasswordAuthenticationToken("member", "n/a");

        var view = controller.candidate(new MemoryController.MemoryCandidateRequest(
                "sys-1", "lesson", "Java 兼容经验", "Java 8 不使用 Map.of。", "wi-1"), actor);

        var captor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(aggregate).insert(captor.capture());
        assertThat(captor.getValue().metadataJson()).contains("\"category\":\"lesson\"", "\"workItemId\":\"wi-1\"");
        assertThat(view.title()).isEqualTo("Java 兼容经验");
        assertThat(view.workItemId()).isEqualTo("wi-1");
    }

    @Test
    void approveCanEditCandidateBeforeActivation() {
        var memories = mock(MemoryItemRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        when(memories.findById("mem-1")).thenReturn(Optional.of(memory("mem-1", "candidate")));
        var controller = new MemoryController(memories, mock(DomainEventService.class), mock(SystemAccessService.class),
                mock(ContextManifestService.class), aggregate, new ObjectMapper());

        var view = controller.approve("mem-1", new MemoryController.ApprovalRequest(
                "constraint", "数据库迁移约束", "禁止修改已有迁移文件。"),
                new UsernamePasswordAuthenticationToken("owner", "n/a"));

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.title()).isEqualTo("数据库迁移约束");
        verify(aggregate).update(argThat((MemoryItem item) -> "禁止修改已有迁移文件。".equals(item.content())
                && item.metadataJson().contains("\"category\":\"constraint\"")));
    }

    @Test
    void workerSnapshotReturnsApprovedMemoriesAndWritesManifest() {
        var memories = mock(MemoryItemRepository.class);
        var manifests = mock(ContextManifestService.class);
        var approved = memory("mem-approved", "approved");
        when(memories.findBySystemIdAndStatus("sys-1", "approved")).thenReturn(List.of(approved));
        when(manifests.create("sys-1", "wi-1", List.of(approved))).thenReturn("manifest-1");
        var controller = new MemoryController(memories, mock(DomainEventService.class), mock(SystemAccessService.class),
                manifests, mock(JdbcAggregateTemplate.class), new ObjectMapper());

        var snapshot = controller.workerSnapshot(new MemoryController.SnapshotRequest("sys-1", "wi-1"));

        assertThat(snapshot.manifestId()).isEqualTo("manifest-1");
        assertThat(snapshot.approvedMemories()).containsExactly(approved);
        verify(manifests).create("sys-1", "wi-1", List.of(approved));
    }

    @Test
    void uiSnapshotPreviewDoesNotWriteManifest() {
        var memories = mock(MemoryItemRepository.class);
        var manifests = mock(ContextManifestService.class);
        var access = mock(SystemAccessService.class);
        var approved = memory("mem-approved", "approved");
        when(memories.findBySystemIdAndStatus("sys-1", "approved")).thenReturn(List.of(approved));
        var controller = new MemoryController(memories, mock(DomainEventService.class), access,
                manifests, mock(JdbcAggregateTemplate.class), new ObjectMapper());

        var snapshot = controller.snapshot("sys-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(snapshot.manifestId()).isNull();
        assertThat(snapshot.approvedMemories()).containsExactly(approved);
        verify(access).requireMember(eq("sys-1"), any());
        verifyNoInteractions(manifests);
    }

    @Test
    void rejectedMemoryIsNotReturnedInSnapshot() {
        var memories = mock(MemoryItemRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var manifests = mock(ContextManifestService.class);
        when(memories.findById("mem-1")).thenReturn(Optional.of(memory("mem-1", "candidate")));
        when(memories.findBySystemIdAndStatus("sys-1", "approved")).thenReturn(List.of());
        when(manifests.create("sys-1", "wi-1", List.of())).thenReturn("manifest-empty");
        var aggregate = mock(JdbcAggregateTemplate.class);
        var controller = new MemoryController(memories, events, access, manifests, aggregate, new ObjectMapper());
        var actor = new UsernamePasswordAuthenticationToken("owner", "n/a");

        controller.reject("mem-1", actor);
        var snapshot = controller.workerSnapshot(new MemoryController.SnapshotRequest("sys-1", "wi-1"));

        assertThat(snapshot.approvedMemories()).isEmpty();
        verify(aggregate).update(argThat((MemoryItem memory) -> "rejected".equals(memory.status())));
    }

    private MemoryItem memory(String id, String status) {
        var now = Instant.now();
        return new MemoryItem(id, "sys-1", "记忆内容", status, "evt-1", null, "{}", "user", now, null);
    }
}
