package com.example.relationshipagent.common.exception;

/**
 * 系统内部错误 → 5xx HTTP 响应。
 */
public class SystemException extends RuntimeException {

    public SystemException(String message) {
        super(message);
    }

    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
