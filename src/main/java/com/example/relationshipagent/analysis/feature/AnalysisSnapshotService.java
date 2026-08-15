package com.example.relationshipagent.analysis.feature;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.statistics.StatisticsCache;
import com.example.relationshipagent.statistics.StatisticsCacheRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the immutable, auditable version identity used by later report orchestration.
 */
@Service
public class AnalysisSnapshotService {

    private final ChatFileRepository chatFileRepository;
    private final MessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final RetrievalChunkRepository chunkRepository;
    private final StatisticsCacheRepository statisticsCacheRepository;
    private final RelationshipAgentProperties properties;

    public AnalysisSnapshotService(ChatFileRepository chatFileRepository,
                                   MessageRepository messageRepository,
                                   ConversationSessionRepository sessionRepository,
                                   RetrievalChunkRepository chunkRepository,
                                   StatisticsCacheRepository statisticsCacheRepository,
                                   RelationshipAgentProperties properties) {
        this.chatFileRepository = chatFileRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.chunkRepository = chunkRepository;
        this.statisticsCacheRepository = statisticsCacheRepository;
        this.properties = properties;
    }

    public AnalysisSnapshot create(String chatFileId) {
        ChatFile file = chatFileRepository.selectById(chatFileId);
        if (file == null) throw new IllegalArgumentException("Chat file does not exist: " + chatFileId);

        List<Message> messages = messageRepository.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatFileId, chatFileId)
                .orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId));
        List<ConversationSession> sessions = sessionRepository.selectList(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId));
        List<RetrievalChunk> chunks = chunkRepository.selectList(new LambdaQueryWrapper<RetrievalChunk>()
                .eq(RetrievalChunk::getChatFileId, chatFileId));
        StatisticsCache statistics = statisticsCacheRepository.selectOne(new LambdaQueryWrapper<StatisticsCache>()
                .eq(StatisticsCache::getChatFileId, chatFileId));

        Instant first = messages.isEmpty() ? null : messages.get(0).getMessageTime();
        Instant last = messages.isEmpty() ? null : messages.get(messages.size() - 1).getMessageTime();
        String embeddingModel = chunks.stream().map(RetrievalChunk::getEmbeddingModel)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().findFirst().orElse("");
        String chunkVersion = chunks.stream().map(RetrievalChunk::getEmbeddingVersion)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().findFirst().orElse("");
        return new AnalysisSnapshot(chatFileId, nullToEmpty(file.getSourceSha256()), first, last,
                messages.size(), sessions.size(), chunks.size(),
                statistics == null ? null : statistics.getComputedAt(),
                sha256(statistics == null ? "" : nullToEmpty(statistics.getStatsJson())),
                chunkVersion, embeddingModel, properties.analysis().analysisVersion());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
