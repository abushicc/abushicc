package com.example.relationshipagent.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.memory.model.MemoryExtractionBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryExtractionBatchRepository extends BaseMapper<MemoryExtractionBatch> {
}
