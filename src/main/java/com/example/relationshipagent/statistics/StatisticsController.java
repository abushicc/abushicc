package com.example.relationshipagent.statistics;

import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 统计查询(M6):GET /api/chat-files/{chatFileId}/statistics 返回缓存 JSON(设计文档 9.17)。
 * 无缓存时 404,提示先构建会话。
 *
 * <p>阶段 2 M6.3：新增 POST /statistics/recompute 手动重算接口。
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}")
public class StatisticsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<Object>> get(@PathVariable UUID chatFileId) {
        String json = statisticsService.getCached(chatFileId.toString());
        if (json == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "统计缓存不存在，请先构建会话");
        }
        try {
            // 解析为对象,避免 stats_json 字符串被二次转义
            Object parsed = MAPPER.readValue(json, Object.class);
            return ResponseEntity.ok(ApiResponse.ok(parsed));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "统计缓存解析失败");
        }
    }

    /**
     * 手动重算统计（M6.3：调整 catchphrases/停用词后触发）
     */
    @PostMapping("/statistics/recompute")
    public ResponseEntity<ApiResponse<Object>> recompute(@PathVariable UUID chatFileId) {
        statisticsService.computeAndCache(chatFileId.toString());
        return get(chatFileId);
    }
}
