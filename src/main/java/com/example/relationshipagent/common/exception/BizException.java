package com.example.relationshipagent.common.exception;

/**
 * 业务可预期异常 → 4xx HTTP 响应。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.detail = null;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail != null ? errorCode.message() + ": " + detail : errorCode.message());
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }
}
