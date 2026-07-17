package com.asterism.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class ModelConnectionClient {
    private static final Logger log = LoggerFactory.getLogger(ModelConnectionClient.class);
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String token;

    public ModelConnectionClient(ObjectMapper objectMapper,
            @Value("${asterism.product-agent.url:http://127.0.0.1:8090/prd-draft}") String productAgentEndpoint,
            @Value("${asterism.worker-callback.token:dev-worker-token}") String token) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(productAgentEndpoint).resolve("/model-connection-test").toString();
        this.token = token;
    }

    public ConnectionResult test(String systemId, String profileId) {
        try {
            // agent-service 与真实模型调用共用出站代理，避免控制面网络差异造成误判。
            var uri = URI.create(endpoint + "?system_id=" + encode(systemId) + "&profile_id=" + encode(profileId));
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConnectionResult(false, "连接失败（HTTP " + response.statusCode() + "）");
            }
            var result = objectMapper.readValue(response.body(), ConnectionResult.class);
            log.info("模型连通性测试 system={} profileId={} connected={}", systemId, profileId, result.connected());
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new ConnectionResult(false, "连接测试被中断");
        } catch (Exception error) {
            log.warn("模型连通性测试失败 system={} profileId={} type={}",
                    systemId, profileId, error.getClass().getSimpleName());
            return new ConnectionResult(false, "连接失败（" + error.getClass().getSimpleName() + "）");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record ConnectionResult(boolean connected, String message) {
    }
}
