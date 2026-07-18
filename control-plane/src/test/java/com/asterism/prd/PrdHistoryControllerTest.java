package com.asterism.prd;

import com.asterism.identity.JdbcUserAccountService;
import com.asterism.identity.SystemAccessService;
import com.asterism.identity.UserAccountView;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.projection.WorkItemProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdHistoryControllerTest {
    @Test
    void returnsCreatorDisplayNameWithoutChangingStoredCreator() {
        var sessions = mock(PrdSessionRepository.class);
        var access = mock(SystemAccessService.class);
        var users = mock(JdbcUserAccountService.class);
        var actor = new UsernamePasswordAuthenticationToken("admin", "n/a");
        var now = Instant.parse("2026-07-11T00:00:00Z");
        var session = new PrdSession("prd-1", "system-1", "conversation-1", null, null,
                "测试 PRD", "目标", "{}", "[]", "waiting_user_confirm", "admin",
                null, null, now, now);
        when(sessions.findBySystemIdOrderByUpdatedAtDesc("system-1")).thenReturn(List.of(session));
        when(users.listUsers()).thenReturn(List.of(new UserAccountView("admin", "Admin", null, true)));
        var controller = new PrdHistoryController(sessions, access, users, mock(WorkItemProjectionRepository.class), new ObjectMapper());

        var result = controller.list("system-1", actor);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.createdBy()).isEqualTo("admin");
            assertThat(view.creatorDisplayName()).isEqualTo("Admin");
            assertThat(view.canDelete()).isTrue();
        });
    }

    @Test
    void returnsDisplayWorkItemIdForHistoricalSession() {
        var sessions = mock(PrdSessionRepository.class);
        var access = mock(SystemAccessService.class);
        var users = mock(JdbcUserAccountService.class);
        var workItems = mock(WorkItemProjectionRepository.class);
        var actor = new UsernamePasswordAuthenticationToken("admin", "n/a");
        var now = Instant.parse("2026-07-06T07:45:22Z");
        var internalId = "wi-prd-c9a539f5-d582-4949-96af-bb22000488be";
        var session = new PrdSession("prd-1", "system-1", "conversation-1", internalId, "case-1",
                "历史 PRD", "目标", "{}", "[]", "completed", "admin", null, null, now, now);
        var item = new WorkItemProjection(internalId, "WI20260706001", "system-1", "prd-1", "case-1", "历史 PRD",
                "completed", "approved", false, "已完成", "", "admin", false, 1, now, now, "admin", now, now);
        when(sessions.findBySystemIdOrderByUpdatedAtDesc("system-1")).thenReturn(List.of(session));
        when(users.listUsers()).thenReturn(List.of());
        when(workItems.findById(internalId)).thenReturn(Optional.of(item));
        var controller = new PrdHistoryController(sessions, access, users, workItems, new ObjectMapper());

        var result = controller.list("system-1", actor);

        assertThat(result).singleElement().extracting(PrdHistoryController.PrdSessionView::workItemId)
                .isEqualTo("WI20260706001");
    }
}
