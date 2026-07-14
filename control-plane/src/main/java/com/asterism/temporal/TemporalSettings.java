package com.asterism.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asterism.temporal")
public record TemporalSettings(String target, String namespace, String taskQueue) {
}

