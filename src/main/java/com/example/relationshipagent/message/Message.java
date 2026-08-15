package com.example.relationshipagent.message;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 消息实体 — CSV 每一行解析成一条记录（设计文档 5.2 / 6.2 DDL message）。
 *
 * <p>是整个系统的核心"原料"表。27,227 条消息中每条都是一次发言，
 * 说话人由 CSV 的 IsSender 字段（0/1）判定，不依赖 NickName（避免 GB18030 编码比对问题）。
 */
@Data
@TableName("message")
public class Message {

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
     * 说话人（kiwi 或对方昵称）
     */
    private String speaker;

    /**
     * 原始消息内容
     */
    private String content;

    /**
     * 清洗后的内容：HTML 反转义、表情包替换为 [描述]、连续空白压缩
     */
    private String cleanedContent;

    /**
     * 消息发送时间（UTC）
     */
    private Instant messageTime;

    /**
     * 消息类型（见常量）
     */
    private String messageType;

    /**
     * 微信原始 LocalId（CSV 第一列）
     */
    private Long sourceLocalId;

    /**
     * CSV 中的行号（首行为表头，消息从第 2 行开始）
     */
    private Integer sourceLineNo;

    /**
     * 入库时间
     */
    private Instant createdAt;

    // ---- 消息类型常量 ----
    public static final String TYPE_TEXT = "TEXT";     // 文字
    public static final String TYPE_EMOJI = "EMOJI";    // 表情包
    public static final String TYPE_IMAGE = "IMAGE";    // 图片
    public static final String TYPE_VOICE = "VOICE";    // 语音
    public static final String TYPE_VIDEO = "VIDEO";    // 视频
    public static final String TYPE_SYSTEM = "SYSTEM";   // 系统提示
    public static final String TYPE_RECALL = "RECALL";   // 撤回消息
    public static final String TYPE_FILE = "FILE";     // 文件
    public static final String TYPE_LOCATION = "LOCATION"; // 位置
}
