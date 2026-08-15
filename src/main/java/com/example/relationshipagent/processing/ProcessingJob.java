package com.example.relationshipagent.processing;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 异步任务实体 — 跟踪每个处理阶段的执行状态（设计文档 6.2 DDL processing_job）。
 *
 * <p>核心机制：
 * <ul>
 *   <li><b>幂等</b>：{chatFileId, jobType, inputHash} 唯一约束，相同输入自动跳过；</li>
 *   <li><b>僵死接管</b>：RUNNING 超过 30 分钟无响应 → {@link ProcessingJobService#tryRetakeStale} 接管；</li>
 *   <li><b>整清重跑</b>：重跑某阶段前先删除该阶段产物（PARSE 清 message，SESSIONIZE 清 session），
 *       FAILED/CANCELLED 状态自动重置为 PENDING。</li>
 * </ul>
 */
@Data
@TableName("processing_job")
public class ProcessingJob {

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
     * 任务类型（PARSE / SESSIONIZE / CHUNK / EMBED）
     */
    private String jobType;

    /**
     * 输入 SHA-256 哈希，用于幂等：相同输入 + 相同 jobType → 跳过
     */
    private String inputHash;

    /**
     * 当前状态（PENDING → RUNNING → SUCCESS / FAILED / CANCELLED）
     */
    private String status;

    /**
     * 断点续跑游标 JSON（阶段 2 EMBED 使用，当前保留不用）
     */
    private String cursorJson;

    /**
     * 当前进度（已处理条数）
     */
    private Integer progressCurrent;

    /**
     * 总进度（待处理总条数）
     */
    private Integer progressTotal;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 最近一次失败的错误消息
     */
    private String errorMessage;

    /**
     * 开始执行时间
     */
    private Instant startedAt;

    /**
     * 当前 worker 租约；每次 stale takeover 更换，用于阻止旧 worker 写回。
     */
    private String leaseToken;

    /**
     * 完成时间
     */
    private Instant finishedAt;

    /**
     * 创建时间
     */
    private Instant createdAt;

    // ---- 状态常量 ----
    public static final String STATUS_PENDING = "PENDING";    // 等待执行
    public static final String STATUS_RUNNING = "RUNNING";    // 执行中
    public static final String STATUS_SUCCESS = "SUCCESS";    // 成功
    public static final String STATUS_FAILED = "FAILED";     // 失败（可重跑）
    public static final String STATUS_CANCELLED = "CANCELLED";  // 已取消

    // ---- 任务类型常量 ----
    public static final String TYPE_PARSE = "PARSE";       // CSV 解析
    public static final String TYPE_SESSIONIZE = "SESSIONIZE";  // 会话构建
    public static final String TYPE_CHUNK = "CHUNK";       // 文本切块（阶段 2）
    public static final String TYPE_EMBED = "EMBED";       // 向量化（阶段 2）
    public static final String TYPE_STATISTICS = "STATISTICS";  // 统计计算
    public static final String TYPE_ANALYSIS = "ANALYSIS";    // 阶段 3 报告生成
    public static final String TYPE_MEMORY_EXTRACT = "MEMORY_EXTRACT";
    public static final String TYPE_MEMORY_AGGREGATE = "MEMORY_AGGREGATE";
    public static final String TYPE_MEMORY_EMBED = "MEMORY_EMBED";
    public static final String TYPE_PERSONA_BUILD = "PERSONA_BUILD";
}
