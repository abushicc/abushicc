package com.example.relationshipagent.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.model.MemorySimilarityCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryItemRepository extends BaseMapper<MemoryItem> {
    void updateEmbedding(@Param("id") String id, @Param("vectorText") String vectorText, @Param("model") String model);

    List<MemoryItem> selectPendingEmbed(@Param("chatFileId") String chatFileId, @Param("model") String model, @Param("limit") int limit);

    long countPendingEmbed(@Param("chatFileId") String chatFileId, @Param("model") String model);

    List<MemorySimilarityCandidate> selectSimilarDifferentKey(@Param("memoryId") String memoryId, @Param("limit") int limit);
}
