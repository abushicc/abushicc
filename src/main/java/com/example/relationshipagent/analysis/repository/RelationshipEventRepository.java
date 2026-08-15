package com.example.relationshipagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.analysis.model.RelationshipEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RelationshipEventRepository extends BaseMapper<RelationshipEvent> {

    /**
     * JSONB needs an explicit cast because MyBatis binds the metrics value as VARCHAR.
     */
    @Insert("""
            INSERT INTO relationship_event (id, chat_file_id, event_type, start_time, end_time, statement, evidence,
              counter_evidence, confidence, source, review_status, created_at, event_key, detector_version,
              input_hash, metrics_json, updated_at)
            VALUES (#{event.id}, #{event.chatFileId}, #{event.eventType}, #{event.startTime}, #{event.endTime},
              #{event.statement}, #{event.evidence}, #{event.counterEvidence}, #{event.confidence}, #{event.source},
              #{event.reviewStatus}, #{event.createdAt}, #{event.eventKey}, #{event.detectorVersion}, #{event.inputHash},
              CAST(#{event.metricsJson} AS jsonb), #{event.updatedAt})
            """)
    int insertWithJsonb(@Param("event") RelationshipEvent event);
}
