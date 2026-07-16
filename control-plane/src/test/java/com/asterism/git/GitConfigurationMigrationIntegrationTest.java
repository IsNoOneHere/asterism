package com.asterism.git;

import com.asterism.IntegrationDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GitConfigurationMigrationIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;

    @Test
    void legacyRepositoryFieldsBecomeOneRepo() throws Exception {
        var systemId = "sys-git-migration-" + UUID.randomUUID();
        jdbc.sql("""
                insert into systems(system_id, name, repo_path, owner_user_id, allowed_paths, forbidden_paths, test_commands)
                values (:systemId, '旧单仓', '/srv/legacy', 'admin', '["src"]', '["secret"]', '["make test"]')
                """).param("systemId", systemId).update();

        ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(dataSource),
                new ClassPathResource("db/migration/V9__git_integration_configuration.sql"));

        var row = jdbc.sql("""
                select repos_json::text from system_git_configs where system_id = :systemId
                """).param("systemId", systemId).query(String.class).single();
        var repo = objectMapper.readTree(row).get(0);
        assertThat(repo.get("repoId").asText()).isEqualTo("main");
        assertThat(repo.get("localPath").asText()).isEqualTo("/srv/legacy");
        assertThat(repo.get("allowedPaths").get(0).asText()).isEqualTo("src");
        assertThat(repo.get("forbiddenPaths").get(0).asText()).isEqualTo("secret");
        assertThat(repo.get("testCommands").get(0).asText()).isEqualTo("make test");
    }
}
