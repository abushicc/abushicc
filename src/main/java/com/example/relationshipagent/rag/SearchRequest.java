package com.example.relationshipagent.rag;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 混合检索请求 DTO（设计文档 7.5.2 / 本手册 M5.3）。
 */
public record SearchRequest(
        @NotBlank String query,
        String speaker,          // 可空：仅检索含该说话人的块
        Instant startTime,       // 可空：会话时间窗下界
        Instant endTime,         // 可空：会话时间窗上界
        String sessionType,      // 可空：GENERAL/CONFLICT/EMOTIONAL/FIRST_MEET
        @Min(1) Integer topK,    // 可空，默认 5，上限 20
        String sortBy            // 可空：score(默认)/timeAsc/timeDesc
) {
    /**
     * 解析后的 topK（默认 5，上限 20）
     */
    public int resolvedTopK() {
        return topK != null ? Math.min(topK, 20) : 5;
    }

    /**
     * 解析后的 sort（默认 score）
     */
    public String resolvedSort() {
        return sortBy != null ? sortBy : "score";
    }
}
