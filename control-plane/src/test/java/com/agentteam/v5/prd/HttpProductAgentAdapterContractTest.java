package com.agentteam.v5.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProductAgentAdapterContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void draftRequestUsesAgentServiceSnakeCaseContract() throws Exception {
        var request = new HttpProductAgentAdapter.DraftRequest(
                "system-1",
                "做登录页",
                Map.of(),
                List.of(),
                List.of(),
                List.of("只能修改 src 目录"));

        var json = objectMapper.writeValueAsString(request);
        assertThat(json).contains("system_id", "current_draft", "missing_fields", "conversation_history", "approved_memories");
        assertThat(json).doesNotContain("systemId", "currentDraft", "missingFields", "conversationHistory", "approvedMemories");
    }

    @Test
    void draftResultReadsAgentServiceSnakeCaseContract() throws Exception {
        var fixture = Files.readString(Path.of("../docs/fixtures/prd-draft-response.json"));

        var result = objectMapper.readValue(fixture, ProductAgentPort.DraftResult.class);

        assertThat(result.missingFields()).containsExactly("acceptance_criteria");
        assertThat(result.assistantMessage()).contains("验收标准");
    }
}
