package com.asterism.system;

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
class ModelConfigurationMigrationIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper objectMapper;

    @Test
    void oldBusinessModelsAreMigratedOnce() throws Exception {
        var systemId = "sys-model-migration-" + UUID.randomUUID();
        jdbc.sql("""
                insert into systems(system_id, name, repo_path, owner_user_id, agent_config, model_provider_config)
                values (:systemId, '迁移测试', '/tmp', 'admin', cast(:agentConfig as jsonb), cast(:modelConfig as jsonb))
                """)
                .param("systemId", systemId)
                .param("agentConfig", "{\"executionProvider\":\"http\",\"executionTimeoutSeconds\":300}")
                .param("modelConfig", """
                        {"businessModels":[{"modelId":"bm-main","name":"主模型","preset":"deepseek",
                          "model":"deepseek-chat","baseUrl":"https://llm.example","apiKey":"secret"}],
                         "businessRouting":{"defaultModelId":"bm-main","prdModelId":"bm-main"},
                         "modelProfiles":[],"agentRoles":[]}
                        """)
                .update();

        // 测试直接重放幂等迁移，覆盖真实旧行而不依赖测试库启动顺序。
        var connection = DataSourceUtils.getConnection(dataSource);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__unify_model_configuration.sql"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__collapse_agent_configuration.sql"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__collapse_agent_configuration.sql"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V15__unify_claude_sdk_team.sql"));

        var config = objectMapper.readTree(jdbc.sql("""
                select model_provider_config::text from systems where system_id = :systemId
                """).param("systemId", systemId).query(String.class).single());
        assertThat(config.at("/modelProfiles/0/id").asText()).isEqualTo("bm-main");
        assertThat(config.at("/modelProfiles/0/provider").asText()).isEqualTo("openai-compat");
        assertThat(config.at("/agents/0/name").asText()).isEqualTo("product");
        assertThat(config.at("/agents/0/modelProfileRef").asText()).isEqualTo("bm-main");
        assertThat(config.at("/agents/1/name").asText()).isEqualTo("developer");
        assertThat(config.at("/agents/1/engine").asText()).isEqualTo("claude_sdk_team");
        assertThat(config.at("/agents/1/modelProfileRef").asText()).isEqualTo("bm-main");
        assertThat(config.get("agents")).hasSize(2);
        assertThat(config.at("/executionMigration/migrated").asBoolean()).isTrue();
        assertThat(config.at("/executionMigration/from/0").asText()).isEqualTo("http");
        assertThat(config.get("modelRouting")).isNull();
        assertThat(config.get("agentRoles")).isNull();
        assertThat(config.get("defaultAgentRoleId")).isNull();
        assertThat(config.get("executionMode")).isNull();
        assertThat(config.get("businessModels")).isNull();
        assertThat(config.get("businessRouting")).isNull();
        assertThat(config.get("modelProfiles")).hasSize(1);
    }
}
