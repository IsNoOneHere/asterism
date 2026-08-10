package com.asterism.prd;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAgentExecutionMigrationContractTest {
    @Test
    void migrationPreservesSystemMessagesAndOnlyRemovesLegacyPendingRows() throws Exception {
        var resource = new ClassPathResource("db/migration/V21__product_agent_execution.sql");
        var sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("check (sender_type in ('user', 'assistant', 'system'))");
        assertThat(sql).contains("delete from conversation_messages where sender_type = 'assistant_pending'");
        assertThat(sql).doesNotContain("delete from conversation_messages where sender_type = 'system'");
    }
}
