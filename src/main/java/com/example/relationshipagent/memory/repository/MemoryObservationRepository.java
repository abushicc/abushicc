package com.example.relationshipagent.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.memory.model.MemoryObservation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryObservationRepository extends BaseMapper<MemoryObservation> {
}
