package com.example.relationshipagent.media;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 媒体附件实体 — 非文本消息的文件关联（设计文档 6.2 DDL message_media）。
 *
 * <p>IMAGE/VOICE/VIDEO 类型的消息各有一条对应记录，source_ref 为磁盘上的真实文件路径；
 * FILE 类型不做自动关联（文件名无时间戳），source_ref 恒为 NULL。
 * 阶段 2 会填充 content_hash 和 OCR/ASR 提取文字。
 */
@Data
@TableName("message_media")
public class MessageMedia {

    /**
     * 主键（UUID v4 字符串）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 所属消息 ID
     */
    private String messageId;

    /**
     * 媒体类型（IMAGE / VOICE / VIDEO / FILE）
     */
    private String mediaType;

    /**
     * 磁盘上的真实文件相对路径（基于 chat-history.root-dir），未命中则为 NULL
     */
    private String sourceRef;

    /**
     * 文件内容 SHA-256（阶段 2 填充）
     */
    private String contentHash;

    /**
     * 提取状态：NOT_REQUESTED → EXTRACTING → EXTRACTED / FAILED
     */
    private String extractionStatus;

    /**
     * OCR/ASR 提取的文字内容（阶段 2 填充）
     */
    private String extractedText;

    /**
     * 提取器版本号
     */
    private String extractorVersion;

    /**
     * 创建时间
     */
    private Instant createdAt;

    // ---- 提取状态常量 ----
    public static final String STATUS_NOT_REQUESTED = "NOT_REQUESTED";  // 尚未请求提取
    public static final String STATUS_EXTRACTING = "EXTRACTING";     // 提取进行中
    public static final String STATUS_EXTRACTED = "EXTRACTED";      // 提取成功
    public static final String STATUS_FAILED = "FAILED";         // 提取失败

    // ---- 媒体类型常量 ----
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VOICE = "VOICE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_FILE = "FILE";
}
