package com.dental.hospital;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException error) {
        HttpStatus status = error.getStatus();
        int code = switch (status) {
            case BAD_REQUEST -> 40001;
            case UNAUTHORIZED -> 40101;
            case FORBIDDEN -> 40301;
            case NOT_FOUND -> 40401;
            case CONFLICT -> error.getReason() != null && error.getReason().contains("幂等键") ? 40904 : 40903;
            default -> status.value() * 100 + 1;
        };
        return ResponseEntity.status(status).body(Map.of("code", code, "message", error.getReason() == null ? status.getReasonPhrase() : error.getReason()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate() {
        return ResponseEntity.status(409).body(Map.of("code", 40903, "message", "记录已存在"));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> locking() {
        return ResponseEntity.status(503).body(Map.of("code", 50301, "message", "数据库繁忙，请稍后重试"));
    }
}
