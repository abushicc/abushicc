package com.example.relationshipagent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 会话实体 — 连续消息按时间间隔切分后的对话单元（设计文档 6.2 DDL conversation_session）。
 *
 * <p>默认切分规则：相邻消息间隔 > 30 分钟 → 新会话；单会话超过 200 条 → 强制切分。
 * 每条会话都有类型标签（GENERAL/EMOTIONAL/CONFLICT/FIRST_MEET），
 * 由 {@link ConversationSessionBuilder#classifySession} 基于关键词统计和消息数自动判定。
 */
@Data
@TableName("conversation_session")
public class ConversationSession {

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
     * 会话开始时间（首条消息的 message_time）
     */
    private Instant startTime;

    /**
     * 会话结束时间（末条消息的 message_time）
     */
    private Instant endTime;

    /**
     * 会话包含的消息数
     */
    private Integer messageCount;

    /**
     * 说话人统计 JSON：{"speaker": count}，service 层手动序列化
     */
    private String speakerStats;

    /**
     * 会话持续秒数（end - start）
     */
    private Integer durationSeconds;

    /**
     * 格式化文本：人类可读的完整对话记录（带时间戳和说话人）
     */
    private String formattedText;

    /**
     * 会话摘要（阶段 2 LLM 填充）
     */
    private String summary;

    /**
     * 会话类型（GENERAL / EMOTIONAL / CONFLICT / FIRST_MEET / IMPORTANT）
     */
    private String sessionType;

    /**
     * 是否因超过单会话消息上限（200 条）而强制切分
     */
    private Boolean forcedSplit;

    /**
     * 创建时间
     */
    private Instant createdAt;

    // ---- 会话类型常量 ----
    public static final String TYPE_GENERAL = "GENERAL";     // 普通对话
    public static final String TYPE_CONFLICT = "CONFLICT";    // 含冲突关键词
    public static final String TYPE_EMOTIONAL = "EMOTIONAL";   // 高情绪密度（>3 个情绪词 或 >80 条）
    public static final String TYPE_IMPORTANT = "IMPORTANT";   // 重要事件关联（阶段 2）
    public static final String TYPE_FIRST_MEET = "FIRST_MEET";  // 两人第一次对话（首会话且为 GENERAL 时标记）
}
