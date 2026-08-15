package com.example.relationshipagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.analysis.model.AnalysisClaim;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalysisClaimRepository extends BaseMapper<AnalysisClaim> {
}
