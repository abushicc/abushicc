package com.example.relationshipagent.retrieval;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.processing.ProcessingJobService;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 检索块构建器：按消息边界滑动窗口切分会话（设计文档 5.3 / 本手册 M2.2）。
 *
 * <p>算法：
 * <pre>
 *   窗口 = target（默认 45），步长 = target - overlap（37）
 *   n ≤ target → 单块
 *   n > target → 滑动窗口 [0,45), [37,82), [74,119), ...
 *   尾窗不足 target 时取末尾 target 条（避免以 2-3 条消息收尾）
 * </pre>
 *
 * <p>retrieval_text 格式与 ConversationSessionBuilder.buildFormattedText 完全一致，
 * 块覆盖不完整会话时追加"（会话内第 x-y 条 / 共 n 条）"。
 */
@Component
public class RetrievalChunkBuilder {

    private static final Logger log = LoggerFactory.getLogger(RetrievalChunkBuilder.class);

    private final RelationshipAgentProperties properties;

    public RetrievalChunkBuilder(RelationshipAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 为一个会话构建检索块列表。
     *
     * @param session      父会话
     * @param messages     会话内消息（按 seq_in_session 升序）
     * @param zoneId       源时区
     * @param mergeSeconds 同说话人消息合并阈值
     * @return 检索块列表（sequence_no 从 1 开始递增）
     */
    public List<RetrievalChunk> build(ConversationSession session, List<Message> messages,
                                      ZoneId zoneId, int mergeSeconds) {
        // 滑动窗口保留相邻上下文；尾窗回退到末尾 target 条，避免最后一个 chunk 只有极少消息。
        int target = properties.chunk().targetMessages();
        int overlap = properties.chunk().overlapMessages();
        int n = messages.size();

        List<RetrievalChunk> chunks = new ArrayList<>();

        if (n <= target) {
            // 单块：覆盖全部消息
            chunks.add(buildChunk(session, messages, 0, n, 1, zoneId, mergeSeconds, null));
        } else {
            int step = target - overlap; // 37
            int seq = 1;
            int start = 0;
            while (start + target < n) {
                int end = start + target;
                chunks.add(buildChunk(session, messages, start, end, seq++, zoneId, mergeSeconds,
                        "（会话内第 " + (start + 1) + "-" + end + " 条 / 共 " + n + " 条）"));
                start += step;
            }
            // 尾窗：不足 target 则取末尾 target 条
            if (start < n) {
                int tailStart = Math.max(0, n - target);
                chunks.add(buildChunk(session, messages, tailStart, n, seq, zoneId, mergeSeconds,
                        "（会话内第 " + (tailStart + 1) + "-" + n + " 条 / 共 " + n + " 条）"));
            }
        }
        return chunks;
    }

    private RetrievalChunk buildChunk(ConversationSession session, List<Message> messages,
                                      int from, int to, int sequenceNo,
                                      ZoneId zoneId, int mergeSeconds, String partialInfo) {
        List<Message> window = messages.subList(from, to);
        String retrievalText = ConversationSessionBuilder.buildFormattedText(
                zoneId, window, mergeSeconds, partialInfo);
        String textHash = ProcessingJobService.hashInput(retrievalText);

        RetrievalChunk chunk = new RetrievalChunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setChatFileId(session.getChatFileId());
        chunk.setParentSessionId(session.getId());
        chunk.setStartMessageId(window.get(0).getId());
        chunk.setEndMessageId(window.get(window.size() - 1).getId());
        chunk.setSequenceNo(sequenceNo);
        chunk.setRetrievalText(retrievalText);
        chunk.setTextHash(textHash);
        chunk.setEmbeddingModel(""); // 建块时尚未向量化
        chunk.setCreatedAt(Instant.now());
        return chunk;
    }
}
