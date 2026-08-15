package com.example.relationshipagent.common.exception;

import com.example.relationshipagent.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * 日志仅打印 ID 与堆栈，不打印消息正文（隐私约束，见设计文档 15.3）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException ex) {
        log.warn("BizException: code={}", ex.errorCode().code());
        int httpStatus = switch (ex.errorCode()) {
            case NOT_FOUND, FILE_NOT_FOUND, ANALYSIS_REPORT_NOT_FOUND, MEMORY_ITEM_NOT_FOUND, PERSONA_PROFILE_NOT_FOUND,
                 COMPANION_SESSION_NOT_FOUND, COMPANION_TURN_NOT_FOUND -> 404;
            case PARAM_INVALID -> 400;
            case ANALYSIS_DISABLED, MEMORY_DISABLED, COMPANION_DISABLED, COMPANION_UPSTREAM_FAILED -> 503;
            case ANALYSIS_PREREQUISITE_MISSING, MEMORY_PREREQUISITE_MISSING, JOB_ALREADY_RUNNING,
                 COMPANION_PERSONA_UNAVAILABLE, COMPANION_SESSION_ENDED, COMPANION_TURN_IN_PROGRESS,
                 COMPANION_REQUEST_CONFLICT -> 409;
            default -> 422;
        };
        String detail = ex.detail() == null || ex.detail().isBlank() ? ex.errorCode().message() : ex.detail();
        return ResponseEntity.status(httpStatus).body(ApiResponse.fail(ex.errorCode(), detail));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", fields);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, fields));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleSystemException(SystemException ex) {
        log.error("SystemException", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("Unhandled exception: {}", ex.getClass().getName(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
