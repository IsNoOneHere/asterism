package com.asterism.git;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabClientTest {
    @Test
    void projectCheckUsesEncodedRestPathAndPrivateTokenHeader() throws Exception {
        var requestPath = new AtomicReference<String>();
        var credential = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/", exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            credential.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new GitLabClient();
            assertThat(client.projectAccessible("http://127.0.0.1:" + server.getAddress().getPort(),
                    "credential-value", "group/web")).isTrue();
            assertThat(requestPath.get()).isEqualTo("/api/v4/projects/group%2Fweb");
            assertThat(credential.get()).isEqualTo("credential-value");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mergeRequestStatusUsesProjectAndIid() throws Exception {
        var requestPath = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/", exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            var body = "{\"state\":\"merged\",\"web_url\":\"https://gitlab/mr/9\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new GitLabClient();
            var status = client.mergeRequest("http://127.0.0.1:" + server.getAddress().getPort(),
                    "credential-value", "group/web", 9);

            assertThat(status.state()).isEqualTo("merged");
            assertThat(status.url()).isEqualTo("https://gitlab/mr/9");
            assertThat(requestPath.get()).isEqualTo("/api/v4/projects/group%2Fweb/merge_requests/9");
        } finally {
            server.stop(0);
        }
    }
}
