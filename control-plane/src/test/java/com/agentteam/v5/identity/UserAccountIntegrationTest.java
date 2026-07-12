package com.agentteam.v5.identity;

import com.agentteam.v5.IntegrationDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserAccountIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcUserAccountService users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Test
    void seededUserAuthenticatesFromUsersTable() throws Exception {
        mockMvc.perform(get("/api/v5/auth/me").with(httpBasic("e2e-user", "agent-team")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("e2e-user"));
    }

    @Test
    void formLoginReturnsApiFriendlyStatusCodes() throws Exception {
        // SPA 用 fetch 登录，成功/失败都返回明确状态码，不能依赖浏览器跳转。
        mockMvc.perform(post("/api/v5/auth/login")
                        .param("username", "admin")
                        .param("password", "agent-team"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v5/auth/login")
                        .param("username", "admin")
                        .param("password", "bad-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotAuthenticate() throws Exception {
        var userId = "disabled-" + UUID.randomUUID();
        users.upsertUser(userId, "Disabled User", userId + "@local", "secret-one");
        users.disableUser(userId);

        mockMvc.perform(get("/api/v5/auth/me").with(httpBasic(userId, "secret-one")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userAdminListDoesNotExposePasswordHash() throws Exception {
        var body = mockMvc.perform(get("/api/v5/users").with(httpBasic("admin", "agent-team")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("e2e-user");
        assertThat(body).doesNotContain("passwordHash", "password_hash", "$2a$", "$2b$", "$2y$");
    }

    @Test
    void upsertWithoutPasswordKeepsExistingPassword() {
        // 管理员改资料时不传 password，只更新资料，不重置用户密码。
        var userId = "keep-pass-" + UUID.randomUUID();
        users.upsertUser(userId, "Keep Pass", userId + "@local", "secret-one");

        users.upsertUser(userId, "Keep Pass Updated", userId + "-2@local", null);

        var passwordHash = jdbc.sql("select password_hash from users where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
        assertThat(encoder.matches("secret-one", passwordHash)).isTrue();
        assertThat(encoder.matches("agent-team", passwordHash)).isFalse();
    }

    @Test
    void newUserMustProvideInitialPassword() {
        var userId = "no-default-pass-" + UUID.randomUUID();

        assertThatThrownBy(() -> users.upsertUser(userId, "No Default", userId + "@local", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("初始密码");
    }
}
