package com.asterism;

import com.asterism.identity.JdbcUserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "asterism.worker-callback.token=test-worker-token")
@AutoConfigureMockMvc
class SystemBusinessIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcUserAccountService users;

    @Test
    void systemCreateIsInsertOnlyAndCreatorAndConfiguredOwnerCanControl() throws Exception {
        var systemId = id("sys-create");

        createSystem(systemId, "e2e-user")
                .andExpect(status().isOk());
        createSystem(systemId, "e2e-user")
                .andExpect(status().isConflict());

        var ownerCount = jdbc.sql("""
                        select count(*) from system_memberships
                        where system_id = :systemId and user_id = 'e2e-user' and role = 'owner'
                        """)
                .param("systemId", systemId)
                .query(Long.class)
                .single();
        assertThat(ownerCount).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*) from system_memberships
                        where system_id = :systemId and user_id = 'e2e-owner' and role = 'owner'
                        """)
                .param("systemId", systemId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void nonOwnerCannotUpdateSystemAndNonMemberCannotSeeIt() throws Exception {
        var systemId = id("sys-private");
        createSystem(systemId, "e2e-owner").andExpect(status().isOk());

        putSystem(systemId, "e2e-user", "Changed")
                .andExpect(status().isForbidden());
        putSystem(systemId, "e2e-owner", "Changed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Changed"));

        var userList = mockMvc.perform(get("/api/v5/systems").with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(userList).doesNotContain(systemId);

        var ownerList = mockMvc.perform(get("/api/v5/systems").with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ownerList).contains(systemId);
    }

    @Test
    void combinedConfigurationFailureRollsBackProfileChanges() throws Exception {
        var systemId = id("sys-atomic-config");
        createSystem(systemId, "e2e-owner").andExpect(status().isOk());

        mockMvc.perform(patch("/api/v5/systems/" + systemId + "/profile")
                        .with(httpBasic("e2e-owner", "asterism"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"不应保存","description":"demo","repoPath":"/tmp/changed",
                                 "ownerUserId":"e2e-owner","allowedPaths":[],"forbiddenPaths":[],"testCommands":[],
                                 "gitConfiguration":{"repos":[]}}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("select name from systems where system_id = :systemId")
                .param("systemId", systemId).query(String.class).single()).isEqualTo("Demo");
    }

    @Test
    void membersCanBeListedAndLastOwnerCannotBeRemoved() throws Exception {
        var systemId = id("sys-members");
        var outsider = id("outsider");
        users.upsertUser(outsider, "Outsider", outsider + "@local", "asterism");
        createSystem(systemId, "e2e-owner").andExpect(status().isOk());
        addMembership(systemId, "e2e-user", "requester").andExpect(status().isOk());

        mockMvc.perform(get("/api/v5/systems/" + systemId + "/members").with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].systemId").value(systemId));
        mockMvc.perform(get("/api/v5/systems/" + systemId + "/members").with(httpBasic(outsider, "asterism")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v5/systems/" + systemId + "/members/e2e-owner/owner")
                        .with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v5/systems/" + systemId + "/members/e2e-user/requester")
                        .with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk());
    }

    @Test
    void emptySystemCanBeDeletedButBusinessSystemCannot() throws Exception {
        var emptySystemId = id("sys-delete-empty");
        createSystem(emptySystemId, "e2e-owner").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v5/systems/" + emptySystemId)
                        .with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from systems where system_id = :systemId")
                .param("systemId", emptySystemId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from system_memberships where system_id = :systemId")
                .param("systemId", emptySystemId).query(Long.class).single()).isZero();

        var usedSystemId = id("sys-delete-used");
        createSystem(usedSystemId, "e2e-owner").andExpect(status().isOk());
        mockMvc.perform(post("/api/v5/systems/" + usedSystemId + "/knowledge")
                        .with(httpBasic("e2e-owner", "asterism"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"page","title":"保留数据","anchorTexts":["不能随系统删除"],
                                 "routePath":"/retain","apiEndpoints":[],"codeRefs":[],"sourceRef":"retain-data"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v5/systems/" + usedSystemId)
                        .with(httpBasic("e2e-owner", "asterism")))
                .andExpect(status().isConflict());
        assertThat(jdbc.sql("select count(*) from systems where system_id = :systemId")
                .param("systemId", usedSystemId).query(Long.class).single()).isOne();
    }

    @Test
    void projectMemoryRequiresMembershipAndRejectsManualCandidateCreation() throws Exception {
        var systemId = id("sys-memory");
        var outsider = id("outsider");
        users.upsertUser(outsider, "Outsider", outsider + "@local", "asterism");
        createSystem(systemId, "e2e-owner").andExpect(status().isOk());
        addMembership(systemId, "e2e-user", "requester").andExpect(status().isOk());

        mockMvc.perform(post("/api/v5/memory/candidates")
                        .with(httpBasic("e2e-user", "asterism"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/api/v5/memory/candidates?systemId=" + systemId + "&status=PENDING")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v5/memory?systemId=" + systemId + "&status=ACTIVE")
                        .with(httpBasic("e2e-user", "asterism")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v5/memory?systemId=" + systemId)
                        .with(httpBasic(outsider, "asterism")))
                .andExpect(status().isForbidden());
    }

    @Test
    void workerTokenCanReadUnmaskedModelConfig() throws Exception {
        var systemId = id("sys-model");
        createSystem(systemId, "e2e-owner").andExpect(status().isOk());
        mockMvc.perform(post("/api/v5/systems/" + systemId + "/model-profiles")
                        .with(httpBasic("e2e-owner", "asterism"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试模型","provider":"openai-compat","model":"gpt-test",
                                 "baseUrl":"https://llm.local","apiKey":"secret-key"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v5/internal/systems/" + systemId + "/model-config"))
                .andExpect(status().isUnauthorized());
        var profileId = jdbc.sql("select model_provider_config #>> '{modelProfiles,0,id}' from systems where system_id = :id")
                .param("id", systemId).query(String.class).single();
        mockMvc.perform(get("/api/v5/internal/systems/" + systemId + "/model-config")
                        .queryParam("profile_id", profileId)
                        .header("Authorization", "Bearer test-worker-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai-compat"))
                .andExpect(jsonPath("$.model").value("gpt-test"))
                .andExpect(jsonPath("$.base_url").value("https://llm.local"))
                .andExpect(jsonPath("$.api_key").value("secret-key"));
    }

    private org.springframework.test.web.servlet.ResultActions createSystem(String systemId, String user) throws Exception {
        return mockMvc.perform(post("/api/v5/systems")
                .with(httpBasic(user, "asterism"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(systemBody(systemId, "Demo")));
    }

    private org.springframework.test.web.servlet.ResultActions putSystem(String systemId, String user, String name) throws Exception {
        return mockMvc.perform(put("/api/v5/systems/" + systemId)
                .with(httpBasic(user, "asterism"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(systemBody(systemId, name)));
    }

    private org.springframework.test.web.servlet.ResultActions addMembership(String systemId, String userId, String role) throws Exception {
        return mockMvc.perform(post("/api/v5/users/memberships")
                .with(httpBasic("admin", "asterism"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"systemId":"%s","userId":"%s","role":"%s"}
                        """.formatted(systemId, userId, role)));
    }

    private String systemBody(String systemId, String name) {
        return """
                {
                  "systemId": "%s",
                  "name": "%s",
                  "description": "demo",
                  "repoPath": "/tmp/%s",
                  "ownerUserId": "e2e-owner",
                  "allowedPaths": ["src"],
                  "forbiddenPaths": ["secrets"],
                  "testCommands": ["mvn test"],
                  "modelProviderConfig": {
                    "provider": "openai",
                    "model": "gpt-test",
                    "baseUrl": "https://llm.local",
                    "apiKey": "secret-key"
                  }
                }
                """.formatted(systemId, name, systemId);
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
