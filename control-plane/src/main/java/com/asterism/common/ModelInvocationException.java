package com.asterism.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;

public class ModelInvocationException extends RuntimeException {
    private final String code;
    private final int status;

    public ModelInvocationException(String code, String message, int status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public static ModelInvocationException from(RestClientResponseException error, ObjectMapper objectMapper) {
        try {
            var body = objectMapper.readTree(error.getResponseBodyAsString());
            var code = body.path("code").asText("MODEL_PROVIDER_ERROR");
            var message = body.path("message").asText("模型服务调用失败");
            return new ModelInvocationException(code, message, error.getStatusCode().value(), error);
        } catch (Exception ignored) {
            return new ModelInvocationException("MODEL_PROVIDER_ERROR", "模型服务调用失败",
                    error.getStatusCode().value(), error);
        }
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
