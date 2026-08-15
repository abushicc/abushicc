package com.example.relationshipagent.chatfile.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 聊天文件实体 — 整个处理管线的"源头"（设计文档 6.2 DDL chat_file）。
 *
 * <p>每条记录代表用户上传的一个微信 CSV，其状态机为：
 * <pre>
 *   UPLOADED → PARSING → PARSED → SESSIONIZING → SESSIONIZED → (阶段2) → READY
 *   ↑                                     │
 *   └─────── ERROR ←──────────────────────┘（任意阶段失败，用户重上传可恢复）
 * </pre>
 *
 * <p>id 使用 String 而非 UUID：PG JDBC 自动处理 String ↔ uuid 双向转换，
 * 无需自定义 TypeHandler（见 阶段1实施问题与解决方案.md §5）。
 */
@Data
@TableName("chat_file")
public class ChatFile {

    /**
     * 主键（UUID v4 字符串）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 原始文件名（如 "耳朵小.csv"）
     */
    private String fileName;

    /**
     * 文件内容 SHA-256，用于幂等去重
     */
    private String sourceSha256;

    /**
     * 源文件格式，固定 "CSV"
     */
    private String sourceFormat;

    /**
     * 导入后文件在磁盘上的绝对路径
     */
    private String filePath;

    /**
     * 文件编码，微信导出默认 GB18030
     */
    private String encoding;

    /**
     * 源时区，默认 Asia/Shanghai（消息时间按此解读）
     */
    private String sourceTimezone;

    /**
     * 解析器版本，与 SHA-256 共同构成唯一约束
     */
    private String parserVersion;

    /**
     * 当前处理状态（见状态常量）
     */
    private String status;

    /**
     * 最近一次失败的错误消息
     */
    private String errorMessage;

    /**
     * 解析出的消息总数（PARSE 完成后填充）
     */
    private Integer messageCount;

    /**
     * 上传时间
     */
    private Instant uploadedAt;

    // ---- 状态常量（处理管线里程碑） ----
    public static final String STATUS_UPLOADED = "UPLOADED";      // 已上传，等待解析
    public static final String STATUS_PARSING = "PARSING";       // 解析进行中
    public static final String STATUS_PARSED = "PARSED";        // 解析完成，等待会话构建
    public static final String STATUS_SESSIONIZING = "SESSIONIZING";  // 会话构建进行中
    public static final String STATUS_SESSIONIZED = "SESSIONIZED";   // 会话构建完成
    public static final String STATUS_CHUNKING = "CHUNKING";      // 文本切块进行中（阶段 2）
    public static final String STATUS_CHUNKED = "CHUNKED";       // 切块完成（阶段 2）
    public static final String STATUS_EMBEDDING = "EMBEDDING";     // 向量化进行中（阶段 2）
    public static final String STATUS_READY = "READY";         // 全管线完成
    public static final String STATUS_ERROR = "ERROR";         // 处理失败，可重试
}
