package com.example.relationshipagent.common.exception;

/**
 * 业务错误码枚举。
 * 所有预期内的业务异常统一使用此枚举，由 @RestControllerAdvice 映射为 HTTP 响应。
 */
public enum ErrorCode {

    // 通用
    PARAM_INVALID(400, "参数校验失败"),
    NOT_FOUND(404, "资源不存在"),

    // 文件
    FILE_NOT_FOUND(4001, "文件不存在"),
    FILE_UNSUPPORTED_FORMAT(4002, "不支持的文件格式"),
    FILE_ALREADY_IMPORTED(4003, "文件已导入（幂等跳过）"),

    // 解析
    PARSE_FAILED(4010, "解析失败"),
    PARSE_ENCODING_ERROR(4011, "编码异常"),

    // 任务
    JOB_ALREADY_RUNNING(4020, "任务已在执行中"),
    JOB_FAILED(4021, "任务执行失败"),
    JOB_MAX_RETRY_EXCEEDED(4022, "超出最大重试次数"),

    // 会话
    SESSION_NOT_FOUND(4030, "会话不存在"),

    // 阶段 2
    CHAT_FILE_NOT_READY(4040, "前置阶段未完成"),
    EMBEDDING_NOT_CONFIGURED(4041, "embedding 模型未配置"),
    ANALYSIS_DISABLED(4050, "分析模型未启用"),
    ANALYSIS_PREREQUISITE_MISSING(4051, "分析前置数据未完成"),
    ANALYSIS_REPORT_NOT_FOUND(4052, "分析报告不存在"),
    MEMORY_DISABLED(4060, "Memory 模型未启用"),
    MEMORY_PREREQUISITE_MISSING(4061, "Memory 前置数据未完成"),
    MEMORY_TARGET_INVALID(4062, "目标人物无有效记忆样本"),
    MEMORY_ITEM_NOT_FOUND(4063, "长期记忆不存在"),
    PERSONA_PROFILE_NOT_FOUND(4064, "人格档案不存在"),
    COMPANION_DISABLED(4070, "Companion 模型未启用"),
    COMPANION_SESSION_NOT_FOUND(4071, "模拟会话不存在"),
    COMPANION_PERSONA_UNAVAILABLE(4072, "模拟会话的人格版本不可用"),
    COMPANION_SESSION_ENDED(4073, "模拟会话已结束或过期"),
    COMPANION_TURN_IN_PROGRESS(4074, "该模拟会话已有消息正在生成"),
    COMPANION_REQUEST_CONFLICT(4075, "clientRequestId 与已有消息不一致"),
    COMPANION_TURN_NOT_FOUND(4076, "模拟回复不存在"),
    COMPANION_UPSTREAM_FAILED(4077, "Companion 上游模型暂时不可用"),

    // 通用业务异常
    IDEMPOTENT_SKIP(4099, "幂等跳过"),
    BUSINESS_ERROR(4100, "业务异常"),

    // 系统
    INTERNAL_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
