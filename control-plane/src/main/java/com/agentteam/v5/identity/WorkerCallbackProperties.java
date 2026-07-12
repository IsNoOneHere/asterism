package com.agentteam.v5.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent-team.worker-callback")
public record WorkerCallbackProperties(String token, String profile) {
    private static final String DEFAULT_TOKEN = "dev-worker-token";

    public String requiredToken() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("worker 回调 token 未配置");
        }
        var currentProfile = profile == null || profile.isBlank() ? "local" : profile;
        if (DEFAULT_TOKEN.equals(token) && !currentProfile.equals("local") && !currentProfile.equals("dev") && !currentProfile.equals("test")) {
            throw new IllegalStateException("非 local/dev profile 不能使用默认 worker token");
        }
        return token;
    }
}
