package com.asterism.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConnectionClientTest {
    @Test
    void forwardsProfileTestToAgentServiceWithInternalToken() throws Exception {
        var query = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = server(200, "{\"connected\":true,\"message\":\"连接正常\"}", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        });
        try {
            var client = client(server);

            var result = client.test("系统 1", "mp/1");

            assertThat(result.connected()).isTrue();
            assertThat(query.get()).isEqualTo("system_id=%E7%B3%BB%E7%BB%9F%201&profile_id=mp%2F1");
            assertThat(authorization.get()).isEqualTo("Bearer internal-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void internalFailureReturnsDisconnectedResult() throws Exception {
        var server = server(503, "{}", exchange -> {});
        try {
            var result = client(server).test("sys-1", "mp-1");

            assertThat(result.connected()).isFalse();
            assertThat(result.message()).isEqualTo("连接失败（HTTP 503）");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsCapabilityTestSeparately() throws Exception {
        var query = new AtomicReference<String>();
        var server = server(200, "{\"supported\":true,\"message\":\"图片输入正常\",\"checkedAt\":\"now\",\"code\":\"\"}",
                exchange -> query.set(exchange.getRequestURI().getRawQuery()));
        try {
            var result = client(server).testCapability("sys-1", "mp-1", "image_input");

            assertThat(result.supported()).isTrue();
            assertThat(query.get()).contains("capability=image_input");
        } finally {
            server.stop(0);
        }
    }

    private ModelConnectionClient client(HttpServer server) {
        return new ModelConnectionClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/model-connection-test", "internal-token");
    }

    private HttpServer server(int status, String body, RequestCapture capture) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model-connection-test", exchange -> {
            capture.accept(exchange);
            var response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/model-capability-test", exchange -> {
            capture.accept(exchange);
            var response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface RequestCapture {
        void accept(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }
}
