package com.asterism.prd;

import com.asterism.common.ModelInvocationException;
import com.asterism.context.ContextItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                List.of(new ContextItem("MEM:mem-1", "memory", "product", "路径约束",
                        "只能修改 src 目录", List.of(), "manual", "hash", 1.0)));

        var json = objectMapper.writeValueAsString(request);
        assertThat(json).contains("system_id", "current_draft", "missing_fields", "conversation_history", "context_items", "MEM:mem-1");
        assertThat(json).doesNotContain("systemId", "currentDraft", "missingFields", "conversationHistory", "approvedMemories");
    }

    @Test
    void draftResultReadsAgentServiceSnakeCaseContract() throws Exception {
        var fixture = Files.readString(Path.of("../docs/fixtures/prd-draft-response.json"));

        var result = objectMapper.readValue(fixture, ProductAgentPort.DraftResult.class);

        assertThat(result.missingFields()).containsExactly("acceptance_criteria");
        assertThat(result.assistantMessage()).contains("验收标准");
    }

    @Test
    void sendsInternalTokenToAgentService() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prd-draft", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = Files.readString(Path.of("../docs/fixtures/prd-draft-response.json"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new HttpProductAgentAdapter(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prd-draft",
                    "internal-token", new ObjectMapper());

            adapter.updateDraft("sys-1", "做登录页", Map.of(), List.of(), List.of(), List.of());

            assertThat(authorization.get()).isEqualTo("Bearer internal-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsStructuredAgentServiceError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prd-draft", exchange -> {
            var response = """
                    {"code":"MODEL_OUTPUT_INVALID","message":"模型输出不符合业务契约"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new HttpProductAgentAdapter(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prd-draft",
                    "internal-token", objectMapper);

            assertThatThrownBy(() ->
                    adapter.updateDraft("sys-1", "做登录页", Map.of(), List.of(), List.of(), List.of()))
                    .isInstanceOf(ModelInvocationException.class)
                    .satisfies(error -> {
                        var modelError = (ModelInvocationException) error;
                        assertThat(modelError.code()).isEqualTo("MODEL_OUTPUT_INVALID");
                        assertThat(modelError.status()).isEqualTo(422);
                        assertThat(modelError.getMessage()).isEqualTo("模型输出不符合业务契约");
                    });
        } finally {
            server.stop(0);
        }
    }
}
