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
                new ProductAgentPort.PrdContent(null, null, null, List.of()),
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

        assertThat(result.patch().title()).isEqualTo("登录页");
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
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prd-memory-candidates",
                    "internal-token", new ObjectMapper());

            adapter.updateDraft("sys-1", "做登录页",
                    new ProductAgentPort.PrdContent(null, null, null, List.of()),
                    List.of(), List.of(), List.of());

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
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prd-memory-candidates",
                    "internal-token", objectMapper);

            assertThatThrownBy(() ->
                    adapter.updateDraft("sys-1", "做登录页",
                            new ProductAgentPort.PrdContent(null, null, null, List.of()),
                            List.of(), List.of(), List.of()))
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

    @Test
    void memoryCandidateRequestUsesIndependentEndpointAndTypedResult() throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prd-memory-candidates", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = """
                    {"candidates":[{"category":"constraint","audience":"both","title":"权限",
                    "content":"仅负责人可发布","target_refs":["page-1"],"evidence_refs":["MEM:rule-1"]}]}
                    """.getBytes(StandardCharsets.UTF_8);
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
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prd-memory-candidates",
                    "internal-token", objectMapper);

            var result = adapter.extractMemoryCandidates(
                    "sys-1",
                    new ProductAgentPort.PrdContent("发布", "明确权限", "code_change", List.of("负责人可发布")),
                    List.of("page-1"),
                    List.of(new ContextItem("MEM:rule-1", "memory", "product", "权限", "负责人批准",
                            List.of("page-1"), "manual", "hash", 1.0)));

            assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.category()).isEqualTo("constraint");
                assertThat(candidate.evidenceRefs()).containsExactly("MEM:rule-1");
            });
            assertThat(body.get()).contains("system_id", "target_refs", "context_items")
                    .doesNotContain("missing_fields", "conversation_history");
        } finally {
            server.stop(0);
        }
    }
}
