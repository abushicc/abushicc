package com.example.relationshipagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.analysis.model.AgentRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRunRepository extends BaseMapper<AgentRun> {
}
