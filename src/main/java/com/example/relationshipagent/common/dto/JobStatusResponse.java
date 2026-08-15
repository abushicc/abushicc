package com.example.relationshipagent.common.dto;

import java.util.List;

/**
 * 任务状态响应（设计文档 17.9）。
 */
public record JobStatusResponse(
        String status,
        String currentJobType,
        int progressCurrent,
        int progressTotal,
        List<String> retryableStages
) {
}
