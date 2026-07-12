package com.agentteam.v5.prd;

import com.agentteam.v5.identity.JdbcUserAccountService;
import com.agentteam.v5.identity.SystemAccessService;
import com.agentteam.v5.identity.UserAccountView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;

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
        var controller = new PrdHistoryController(sessions, access, users, new ObjectMapper());

        var result = controller.list("system-1", actor);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.createdBy()).isEqualTo("admin");
            assertThat(view.creatorDisplayName()).isEqualTo("Admin");
        });
    }
}
