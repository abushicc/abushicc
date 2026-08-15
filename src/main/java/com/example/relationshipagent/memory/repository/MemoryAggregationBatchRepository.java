package com.example.relationshipagent.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.memory.model.MemoryAggregationBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryAggregationBatchRepository extends BaseMapper<MemoryAggregationBatch> {
}
