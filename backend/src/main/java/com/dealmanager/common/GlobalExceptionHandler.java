package com.dealmanager.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        if (e.getStatus().is5xxServerError()) log.error("api.failed status={} reason={}", e.getStatus().value(), LogSafety.text(e.getMessage()));
        else log.warn("api.rejected status={} reason={}", e.getStatus().value(), LogSafety.text(e.getMessage()));
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage(), "code", e.getStatus().value()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<Map<String, Object>> handleValidation(Exception e) {
        log.warn("api.validation_failed errorType={}", e.getClass().getSimpleName());
        String message = e instanceof MethodArgumentNotValidException m && m.getBindingResult().getFieldError() != null
                ? m.getBindingResult().getFieldError().getDefaultMessage() : "请求参数不正确";
        return ResponseEntity.badRequest().body(Map.of("message", message, "code", 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception e) {
        log.error("api.unexpected_error errorType={}", e.getClass().getSimpleName(), LogSafety.exception(e));
        return ResponseEntity.internalServerError().body(Map.of("message", "服务暂时不可用，请稍后重试", "code", 500));
    }
}
