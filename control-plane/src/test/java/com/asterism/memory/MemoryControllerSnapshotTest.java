package com.asterism.memory;

import com.asterism.event.DomainEventService;
import com.asterism.identity.SystemAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
        var candidates = mock(MemoryCandidateService.class);
        when(candidates.create(any())).thenReturn(memory("mem-1", "candidate", "execution",
                "{\"category\":\"lesson\",\"title\":\"Java 兼容经验\",\"workItemId\":\"wi-1\"}"));
        when(candidates.targetRefs(any())).thenReturn(Map.of("mem-1", List.of("page-login")));
        when(candidates.evidenceRefs(any())).thenReturn(List.of());
        var controller = new MemoryController(mock(MemoryItemRepository.class), mock(DomainEventService.class),
                mock(SystemAccessService.class), mock(JdbcAggregateTemplate.class), new ObjectMapper(), candidates);
        var actor = new UsernamePasswordAuthenticationToken("member", "n/a");

        var view = controller.candidate(new MemoryController.MemoryCandidateRequest(
                "sys-1", "lesson", "Java 兼容经验", "Java 8 不使用 Map.of。", "execution",
                List.of("page-login"), "wi-1"), actor);

        verify(candidates).create(argThat(input -> "lesson".equals(input.category())
                && "execution".equals(input.audience()) && input.targetRefs().contains("page-login")));
        assertThat(view.title()).isEqualTo("Java 兼容经验");
        assertThat(view.workItemId()).isEqualTo("wi-1");
        assertThat(view.audience()).isEqualTo("execution");
        assertThat(view.targetRefs()).containsExactly("page-login");
    }

    @Test
    void approveCanEditCandidateBeforeActivation() {
        var memories = mock(MemoryItemRepository.class);
        var candidates = mock(MemoryCandidateService.class);
        var current = memory("mem-1", "candidate");
        when(memories.findById("mem-1")).thenReturn(Optional.of(current));
        when(candidates.targetRefs(any())).thenReturn(Map.of("mem-1", List.of()));
        when(candidates.evidenceRefs(any())).thenReturn(List.of());
        when(candidates.approve(eq(current), any(), eq("owner"))).thenReturn(memory(
                "mem-1", "approved", "both",
                "{\"category\":\"constraint\",\"title\":\"数据库迁移约束\"}"));
        var controller = new MemoryController(memories, mock(DomainEventService.class), mock(SystemAccessService.class),
                mock(JdbcAggregateTemplate.class), new ObjectMapper(), candidates);

        var view = controller.approve("mem-1", new MemoryController.ApprovalRequest(
                "constraint", "数据库迁移约束", "禁止修改已有迁移文件。", "both", List.of()),
                new UsernamePasswordAuthenticationToken("owner", "n/a"));

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.title()).isEqualTo("数据库迁移约束");
        verify(candidates).approve(eq(current), argThat(edit -> "禁止修改已有迁移文件。".equals(edit.content())
                && "constraint".equals(edit.category())), eq("owner"));
    }

    @Test
    void rejectedMemoryLeavesApprovedRecallSet() {
        var memories = mock(MemoryItemRepository.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        when(memories.findById("mem-1")).thenReturn(Optional.of(memory("mem-1", "candidate")));
        var aggregate = mock(JdbcAggregateTemplate.class);
        var candidates = mock(MemoryCandidateService.class);
        when(candidates.targetRefs(any())).thenReturn(Map.of());
        when(candidates.evidenceRefs(any())).thenReturn(List.of());
        var controller = new MemoryController(memories, events, access, aggregate, new ObjectMapper(), candidates);
        var actor = new UsernamePasswordAuthenticationToken("owner", "n/a");

        controller.reject("mem-1", actor);

        verify(aggregate).update(argThat((MemoryItem memory) -> "rejected".equals(memory.status())));
    }

    private MemoryItem memory(String id, String status) {
        return memory(id, status, "both", "{}");
    }

    private MemoryItem memory(String id, String status, String audience, String metadata) {
        var now = Instant.now();
        return new MemoryItem(id, "sys-1", "记忆内容", status, audience, id, "manual:" + id, "[]",
                "hash-" + id, "evt-1", null, metadata, "user", now, null);
    }
}
