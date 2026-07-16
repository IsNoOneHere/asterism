package com.asterism.git;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asterism.gitlab")
public record GitLabProperties(String baseUrl, String token) {
    public String safeBaseUrl() {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public String safeToken() {
        return token == null ? "" : token;
    }
}
