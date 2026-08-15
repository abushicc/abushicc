package com.example.relationshipagent.parser;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 解析异常实体 — CSV 解析过程中跳过的异常行（设计文档 6.2 DDL parse_error）。
 *
 * <p>169 条异常主要来自：时间戳无效、说话人无法判定、文字消息内容为空、
 * 列数不足、消息类型未知等。所有异常都不阻塞解析——跳过该行继续处理。
 */
@Data
@TableName("parse_error")
public class ParseError {

    /**
     * 主键（UUID v4 字符串）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 所属聊天文件 ID
     */
    private String chatFileId;

    /**
     * CSV 行号
     */
    private Integer sourceLineNo;

    /**
     * 原始行内容（截断至 1000 字符）
     */
    private String rawContent;

    /**
     * 异常类型（见常量）
     */
    private String errorType;

    /**
     * 人类可读的异常描述
     */
    private String errorMessage;

    /**
     * 记录时间
     */
    private Instant createdAt;

    // ---- 异常类型常量 ----
    public static final String ERR_ENCODING = "ENCODING";        // 编码异常
    public static final String ERR_FORMAT = "FORMAT";          // 格式异常（列数不足等）
    public static final String ERR_SPEAKER_UNKNOWN = "SPEAKER_UNKNOWN"; // 无法判定说话人
    public static final String ERR_EMPTY_CONTENT = "EMPTY_CONTENT";   // 文字消息内容为空
    public static final String ERR_BAD_TIMESTAMP = "BAD_TIMESTAMP";   // 时间戳无效
    public static final String ERR_UNKNOWN_TYPE = "UNKNOWN_TYPE";    // 未知消息类型
}
