package com.example.relationshipagent.statistics;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 统计缓存实体 — 每个聊天文件一条记录（设计文档 6.2 DDL statistics_cache）。
 *
 * <p>stats_json 包含：totalMessages, timeRange, speakerMessageCount,
 * monthlyMessageTrend, averageReplyDelay (per-speaker P50/P90),
 * sessionCount, averageSessionDuration, mostActiveHours, topKeywords([]占位)。
 * chat_file_id 唯一约束，session 重建时 UPSERT 刷新。
 */
@Data
@TableName("statistics_cache")
public class StatisticsCache {

    /**
     * 主键（UUID v4 字符串）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 所属聊天文件 ID（唯一约束）
     */
    private String chatFileId;

    /**
     * 统计结果 JSON 字符串
     */
    private String statsJson;

    /**
     * 最近一次计算时间
     */
    private Instant computedAt;
}
