package com.asterism.common;

import com.asterism.artifact.ArtifactConflictException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    org.springframework.http.ResponseEntity<ApiError> apiError(ApiException error) {
        return org.springframework.http.ResponseEntity.status(error.status())
                .body(new ApiError(error.code(), error.getMessage(), error.details()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    org.springframework.http.ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    org.springframework.http.ResponseEntity<ApiError> invalidInput(MethodArgumentNotValidException error) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不合法", null);
    }

    @ExceptionHandler(IllegalStateException.class)
    org.springframework.http.ResponseEntity<ApiError> conflict(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, "CONFLICT", error.getMessage(), null);
    }

    @ExceptionHandler(ArtifactConflictException.class)
    org.springframework.http.ResponseEntity<ApiError> artifactConflict(ArtifactConflictException error) {
        return response(HttpStatus.CONFLICT, "ARTIFACT_CONFLICT", error.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    org.springframework.http.ResponseEntity<ApiError> forbidden(AccessDeniedException error) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", error.getMessage(), null);
    }

    // 路由层异常保留标准 HTTP 语义，避免被兜底处理成误导性的 500。
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    org.springframework.http.ResponseEntity<ApiError> notFound(Exception error) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    org.springframework.http.ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不支持", null);
    }

    @ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<ApiError> internal(Exception error) {
        log.error("未处理的 API 异常", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用", null);
    }

    private org.springframework.http.ResponseEntity<ApiError> response(HttpStatus status, String code, String message, Object details) {
        return org.springframework.http.ResponseEntity.status(status).body(new ApiError(code, message, details));
    }

    public record ApiError(String code, String message, Object details) {
    }
}
