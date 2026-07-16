package com.asterism.git;

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

    public GitLabClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    GitLabClient(HttpClient http) {
        this.http = http;
    }

    public boolean projectAccessible(String baseUrl, String token, String project) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank() || project == null || project.isBlank()) {
            return false;
        }
        try {
            var encoded = URLEncoder.encode(project, StandardCharsets.UTF_8).replace("+", "%20");
            var request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/v4/projects/" + encoded))
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
}
