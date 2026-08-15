package com.example.relationshipagent.retrieval;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * retrieval_chunk 表 Mapper — MyBatis-Plus BaseMapper + 自定义向量 SQL（XML mapper）。
 */
@Mapper
public interface RetrievalChunkRepository extends BaseMapper<RetrievalChunk> {

    /**
     * 批量更新 embedding（一个事务内的循环单条 UPDATE，见 EmbeddingBatchWriter）
     */
    void updateEmbedding(@Param("id") String id,
                         @Param("vectorText") String vectorText,
                         @Param("model") String model);

    /**
     * 查询待向量化的块（记录级断点续跑）
     */
    List<RetrievalChunk> selectPendingEmbed(@Param("chatFileId") String chatFileId,
                                            @Param("model") String model,
                                            @Param("limit") int limit);

    /**
     * 统计待向量化的块数
     */
    long countPendingEmbed(@Param("chatFileId") String chatFileId,
                           @Param("model") String model);

    /**
     * 统计关键词出现的 chunk 数，用于计算 IDF 稀有度。
     */
    long countContainingKeyword(@Param("chatFileId") String chatFileId,
                                @Param("keyword") String keyword);

    /**
     * M5 向量检索：pgvector 余弦距离 + 结构化过滤
     */
    List<RetrievalChunk> vectorSearch(@Param("chatFileId") String chatFileId,
                                      @Param("queryVector") String queryVector,
                                      @Param("model") String model,
                                      @Param("startTime") java.time.Instant startTime,
                                      @Param("endTime") java.time.Instant endTime,
                                      @Param("sessionType") String sessionType,
                                      @Param("speaker") String speaker,
                                      @Param("limit") int limit);

    /**
     * M5 关键词检索：SQL 内 ILIKE 多词匹配 + 结构化过滤（兼容降级模式）
     */
    List<RetrievalChunk> keywordSearch(@Param("chatFileId") String chatFileId,
                                       @Param("keywords") List<String> keywords,
                                       @Param("startTime") java.time.Instant startTime,
                                       @Param("endTime") java.time.Instant endTime,
                                       @Param("sessionType") String sessionType,
                                       @Param("speaker") String speaker,
                                       @Param("limit") int limit);
}
