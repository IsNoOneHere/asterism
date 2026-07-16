package com.asterism.git;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class GitLabClient {
    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public GitLabClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    GitLabClient(HttpClient http) {
        this(http, new ObjectMapper());
    }

    GitLabClient(HttpClient http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    public boolean projectAccessible(String baseUrl, String token, String project) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank() || project == null || project.isBlank()) {
            return false;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(projectUrl(baseUrl, project)))
                    .timeout(Duration.ofSeconds(8))
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            var accessible = response.statusCode() >= 200 && response.statusCode() < 300;
            log.info("GitLab 项目连通检查 project={} accessible={} status={}", project, accessible, response.statusCode());
            return accessible;
        } catch (Exception error) {
            log.warn("GitLab 项目连通检查失败 project={} type={}", project, error.getClass().getSimpleName());
            return false;
        }
    }

    public MergeRequestStatus mergeRequest(String baseUrl, String token, String project, int iid) {
        try {
            var request = HttpRequest.newBuilder(URI.create(projectUrl(baseUrl, project) + "/merge_requests/" + iid))
                    .timeout(Duration.ofSeconds(8))
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitLab MR 查询失败 status=" + response.statusCode());
            }
            var json = objectMapper.readTree(response.body());
            return new MergeRequestStatus(iid, json.path("state").asText(), json.path("web_url").asText());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitLab MR 查询被中断", error);
        } catch (Exception error) {
            throw new IllegalStateException("GitLab MR 查询失败", error);
        }
    }

    private String projectUrl(String baseUrl, String project) {
        var encoded = URLEncoder.encode(project, StandardCharsets.UTF_8).replace("+", "%20");
        return baseUrl.replaceAll("/+$", "") + "/api/v4/projects/" + encoded;
    }

    public record MergeRequestStatus(int iid, String state, String url) {
    }
}
