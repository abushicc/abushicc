package com.example.relationshipagent.rag;

import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 批量向量写入器（阶段 2 M3.4）：一个事务内循环单条 UPDATE embedding。
 *
 * <p>独立 bean 确保 @Transactional 经由 Spring 代理生效（复用阶段 1 M2.3 的教训）。
 * 向量文本格式为 {@code [0.123,-0.456,...]}（StringBuilder + Float.toString），pgvector 接受。
 */
@Component
public class EmbeddingBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchWriter.class);

    private final RetrievalChunkRepository chunkRepository;

    public EmbeddingBatchWriter(RetrievalChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public void writeBatch(List<RetrievalChunk> chunks, float[][] vectors, String model) {
        for (int i = 0; i < chunks.size(); i++) {
            String vectorText = toVectorText(vectors[i]);
            chunkRepository.updateEmbedding(chunks.get(i).getId(), vectorText, model);
        }
    }

    /**
     * float[] → "[0.123,-0.456,...]" 文本格式
     */
    static String toVectorText(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(Float.toString(vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
