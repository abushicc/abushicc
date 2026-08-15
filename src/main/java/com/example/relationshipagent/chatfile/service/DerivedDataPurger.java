package com.example.relationshipagent.chatfile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.parser.ParseError;
import com.example.relationshipagent.parser.ParseErrorRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.statistics.StatisticsCache;
import com.example.relationshipagent.statistics.StatisticsCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 派生数据清理器（设计文档 17.7 整清重跑）。
 * <p>
 * PARSE / SESSIONIZE 重跑前先删除该 chat_file 在本阶段及其下游的全部产物,再从头执行。
 * 各表均带 ON DELETE CASCADE:删 conversation_session 级联清 session_message / retrieval_chunk;
 * 删 message 级联清 message_media。此处按显式顺序删除,既保证 FK 顺序,也便于阅读。
 * <p>
 * 独立 bean,供 {@link ChatFileProcessingService} 与 {@link ChatFileService} 复用,
 * 避免两服务互相注入造成的循环依赖。
 */
@Component
public class DerivedDataPurger {

    private static final Logger log = LoggerFactory.getLogger(DerivedDataPurger.class);

    private final ConversationSessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ParseErrorRepository parseErrorRepository;
    private final StatisticsCacheRepository statisticsCacheRepository;

    public DerivedDataPurger(ConversationSessionRepository sessionRepository,
                             MessageRepository messageRepository,
                             ParseErrorRepository parseErrorRepository,
                             StatisticsCacheRepository statisticsCacheRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.parseErrorRepository = parseErrorRepository;
        this.statisticsCacheRepository = statisticsCacheRepository;
    }

    /**
     * 全量清理:会话 → 消息 → 解析异常 → 统计缓存。
     * 用于 PARSE 重跑(M2.1)与 ERROR 重置分支(M2.4)。
     */
    public void purgeAll(String chatFileId) {
        int sessions = sessionRepository.delete(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId));
        int messages = messageRepository.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatFileId, chatFileId));
        int errors = parseErrorRepository.delete(new LambdaQueryWrapper<ParseError>()
                .eq(ParseError::getChatFileId, chatFileId));
        int stats = statisticsCacheRepository.delete(new LambdaQueryWrapper<StatisticsCache>()
                .eq(StatisticsCache::getChatFileId, chatFileId));
        log.info("Purged derived data for chatFileId={}: sessions={}, messages={}, parseErrors={}, stats={}",
                chatFileId, sessions, messages, errors, stats);
    }

    /**
     * 仅清理会话与统计缓存(消息保留,供重新切分)。
     * 用于 SESSIONIZE 重跑(M2.2)。CASCADE 自动清 session_message / retrieval_chunk。
     */
    public void purgeSessionsAndStats(String chatFileId) {
        int sessions = sessionRepository.delete(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId));
        int stats = statisticsCacheRepository.delete(new LambdaQueryWrapper<StatisticsCache>()
                .eq(StatisticsCache::getChatFileId, chatFileId));
        log.info("Purged sessions & stats for chatFileId={}: sessions={}, stats={}",
                chatFileId, sessions, stats);
    }
}
