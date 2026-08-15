package com.example.relationshipagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.analysis.model.RelationshipStage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RelationshipStageRepository extends BaseMapper<RelationshipStage> {

    /**
     * JSONB needs an explicit cast because MyBatis binds the metrics value as VARCHAR.
     */
    @Insert("""
            INSERT INTO relationship_stage (id, chat_file_id, stage_key, stage_type, start_time, end_time,
              metrics_json, summary, confidence, source, review_status, detector_version, input_hash, created_at)
            VALUES (#{stage.id}, #{stage.chatFileId}, #{stage.stageKey}, #{stage.stageType}, #{stage.startTime}, #{stage.endTime},
              CAST(#{stage.metricsJson} AS jsonb), #{stage.summary}, #{stage.confidence}, #{stage.source},
              #{stage.reviewStatus}, #{stage.detectorVersion}, #{stage.inputHash}, #{stage.createdAt})
            """)
    int insertWithJsonb(@Param("stage") RelationshipStage stage);
}
