package com.example.relationshipagent.retrieval;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 检索块实体 — 会话按消息边界滑动窗口切分后的检索单元（设计文档 5.3 / 6.2 DDL retrieval_chunk）。
 *
 * <p>embedding 列（pgvector VECTOR 类型）不进实体——向量读写走原生 SQL CAST（0.5 决策 3）。
 */
@Data
@TableName("retrieval_chunk")
public class RetrievalChunk {

    /**
     * 主键（UUID v4）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 所属聊天文件 ID
     */
    private String chatFileId;

    /**
     * 所属会话 ID
     */
    private String parentSessionId;

    /**
     * 窗口首条消息 ID
     */
    private String startMessageId;

    /**
     * 窗口末条消息 ID
     */
    private String endMessageId;

    /**
     * 块在会话内的序号（从 1 开始）
     */
    private Integer sequenceNo;

    /**
     * 检索文本：格式与 formatted_text 一致
     */
    private String retrievalText;

    /**
     * 块摘要（阶段 3 LLM 填充，当前留空）
     */
    private String summary;

    /**
     * retrieval_text 的 SHA-256
     */
    private String textHash;

    /**
     * 向量化模型名：建块时固定 ''，向量化时 UPDATE 为实际模型名
     */
    private String embeddingModel;

    /**
     * 向量化模型版本（一期与 model 同值）
     */
    private String embeddingVersion;

    /**
     * 向量化完成时间
     */
    private Instant embeddedAt;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 查询时由 mapper 投影的余弦距离；不是数据库字段。
     */
    @TableField(exist = false)
    private Double searchDistance;
}
