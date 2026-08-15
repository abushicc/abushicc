package com.example.relationshipagent.common.dto;

import java.util.List;

/**
 * 分页响应。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
