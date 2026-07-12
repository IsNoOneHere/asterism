package com.agentteam.v5.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent-team.temporal")
public record TemporalSettings(String target, String namespace, String taskQueue) {
}

