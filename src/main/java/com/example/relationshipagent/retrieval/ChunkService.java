package com.example.relationshipagent.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobExecutor;
import com.example.relationshipagent.processing.ProcessingJobService;
import com.example.relationshipagent.analysis.service.AnalysisInvalidationService;
import com.example.relationshipagent.session.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CHUNK 任务服务（阶段 2 M2.3）：将已构建的会话按消息边界切分成检索块。
 *
 * <p>整清重跑：每次 CHUNK 前删除该 chat_file 全部 retrieval_chunk 行，
 * 并级联重置下游 EMBED job 为 PENDING。
 */
@Service
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final ChatFileService chatFileService;
    private final ConversationSessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final MessageRepository messageRepository;
    private final RetrievalChunkRepository chunkRepository;
    private final RetrievalChunkBuilder chunkBuilder;
    private final ProcessingJobService jobService;
    private final ProcessingJobExecutor jobExecutor;
    private final RelationshipAgentProperties properties;
    private final com.example.relationshipagent.rag.RetrievalService retrievalService;
    private final AnalysisInvalidationService analysisInvalidationService;

    public ChunkService(ChatFileService chatFileService,
                        ConversationSessionRepository sessionRepository,
                        SessionMessageRepository sessionMessageRepository,
                        MessageRepository messageRepository,
                        RetrievalChunkRepository chunkRepository,
                        RetrievalChunkBuilder chunkBuilder,
                        ProcessingJobService jobService,
                        ProcessingJobExecutor jobExecutor,
                        RelationshipAgentProperties properties,
                        com.example.relationshipagent.rag.RetrievalService retrievalService,
                        AnalysisInvalidationService analysisInvalidationService) {
        this.chatFileService = chatFileService;
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.messageRepository = messageRepository;
        this.chunkRepository = chunkRepository;
        this.chunkBuilder = chunkBuilder;
        this.jobService = jobService;
        this.jobExecutor = jobExecutor;
        this.properties = properties;
        this.retrievalService = retrievalService;
        this.analysisInvalidationService = analysisInvalidationService;
    }

    /**
     * 触发 CHUNK 任务（异步）。
     *
     * @return jobId
     */
    public String startChunk(String chatFileId) {
        ChatFile chatFile = chatFileService.getById(chatFileId);
        if (!List.of(ChatFile.STATUS_SESSIONIZED, ChatFile.STATUS_CHUNKED, ChatFile.STATUS_READY)
                .contains(chatFile.getStatus())) {
            throw new BizException(ErrorCode.CHAT_FILE_NOT_READY);
        }

        String inputHash = ProcessingJobService.hashInput(
                chatFile.getSourceSha256(), "chunk-v2",
                String.valueOf(properties.chunk().targetMessages()),
                String.valueOf(properties.chunk().overlapMessages()));
        ProcessingJob job = jobService.createOrGet(chatFileId, ProcessingJob.TYPE_CHUNK, inputHash);
        if (job == null) throw new BizException(ErrorCode.IDEMPOTENT_SKIP);
        if (!jobService.tryTakeover(job.getId())) throw new BizException(ErrorCode.JOB_ALREADY_RUNNING);
        chatFileService.updateStatus(chatFileId, ChatFile.STATUS_CHUNKING);
        jobExecutor.submit("chunk-" + chatFileId, () -> doChunk(chatFile, job));
        return job.getId();
    }

    private void doChunk(ChatFile chatFile, ProcessingJob job) {
        try {
            // 1. 整清重跑：删除本 chat_file 全部 retrieval_chunk
            analysisInvalidationService.supersedeForUpstreamRebuild(chatFile.getId(), ProcessingJob.TYPE_CHUNK);
            chunkRepository.delete(new LambdaQueryWrapper<RetrievalChunk>()
                    .eq(RetrievalChunk::getChatFileId, chatFile.getId()));
            retrievalService.invalidateIdfCache(chatFile.getId());
            // 2. 级联重置下游 EMBED job（0.5 决策 2）
            jobService.resetToPending(chatFile.getId(), ProcessingJob.TYPE_EMBED);

            // 3. 逐会话构建
            List<ConversationSession> sessions = sessionRepository.selectList(
                    new LambdaQueryWrapper<ConversationSession>()
                            .eq(ConversationSession::getChatFileId, chatFile.getId())
                            .orderByAsc(ConversationSession::getStartTime));

            ZoneId zoneId = ZoneId.of(chatFile.getSourceTimezone() != null
                    ? chatFile.getSourceTimezone() : "Asia/Shanghai");
            int mergeSeconds = properties.session().displayMergeSeconds();
            int total = sessions.size();

            for (int i = 0; i < total; i++) {
                if (!jobService.isLeaseActive(job.getId())) return;
                ConversationSession s = sessions.get(i);
                List<Message> msgs = loadSessionMessages(s.getId());
                List<RetrievalChunk> chunks = chunkBuilder.build(s, msgs, zoneId, mergeSeconds);
                for (RetrievalChunk c : chunks) chunkRepository.insert(c);
                jobService.updateProgress(job.getId(), i + 1, total);
            }

            if (!jobService.isLeaseActive(job.getId())) return;
            chatFileService.updateStatus(chatFile.getId(), ChatFile.STATUS_CHUNKED);
            jobService.markSuccess(job.getId());
            log.info("Chunk completed: chatFileId={}, sessions={}, chunks~{}",
                    chatFile.getId(), total,
                    chunkRepository.selectCount(new LambdaQueryWrapper<RetrievalChunk>()
                            .eq(RetrievalChunk::getChatFileId, chatFile.getId())));
        } catch (Exception e) {
            log.error("Chunk failed: chatFileId={}", chatFile.getId(), e);
            if (!jobService.isLeaseActive(job.getId())) return;
            jobService.markFailed(job.getId(), e.getMessage());
            chatFileService.updateError(chatFile.getId(), e.getMessage());
        }
    }

    /**
     * 加载一个会话的全部消息（按 seq_in_session 升序）
     */
    private List<Message> loadSessionMessages(String sessionId) {
        List<SessionMessage> sms = sessionMessageRepository.selectList(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .orderByAsc(SessionMessage::getSeqInSession));
        List<String> msgIds = sms.stream().map(SessionMessage::getMessageId).toList();
        if (msgIds.isEmpty()) return List.of();
        List<Message> loaded = messageRepository.selectList(
                new LambdaQueryWrapper<Message>().in(Message::getId, msgIds));
        return orderMessages(msgIds, loaded);
    }

    static List<Message> orderMessages(List<String> orderedIds, List<Message> loaded) {
        Map<String, Message> byId = new HashMap<>();
        for (Message message : loaded) byId.put(message.getId(), message);
        // PostgreSQL 不保证 IN (...) 的返回顺序；恢复 session_message 的显式顺序。
        return orderedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
