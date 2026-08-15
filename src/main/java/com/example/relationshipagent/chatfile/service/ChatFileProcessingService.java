package com.example.relationshipagent.chatfile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.dto.JobStatusResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.parser.*;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobExecutor;
import com.example.relationshipagent.processing.ProcessingJobService;
import com.example.relationshipagent.session.*;
import com.example.relationshipagent.statistics.StatisticsService;
import com.example.relationshipagent.analysis.service.AnalysisInvalidationService;
import com.example.relationshipagent.memory.service.MemoryPersonaInvalidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ChatFileProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileProcessingService.class);
    private static final String SESSIONIZER_VERSION = "sessionize-v2";

    private final ChatFileService chatFileService;
    private final ChatFileRepository chatFileRepository;
    private final CsvChatParser csvParser;
    private final MessageRepository messageRepository;
    private final ParseErrorRepository parseErrorRepository;
    private final ConversationSessionBuilder sessionBuilder;
    private final ConversationSessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final ProcessingJobService jobService;
    private final ProcessingJobExecutor jobExecutor;
    private final RelationshipAgentProperties properties;
    private final ImportBatchWriter importBatchWriter;
    private final DerivedDataPurger derivedDataPurger;
    private final StatisticsService statisticsService;
    private final AnalysisInvalidationService analysisInvalidationService;
    private final MemoryPersonaInvalidationService memoryPersonaInvalidationService;

    public ChatFileProcessingService(ChatFileService chatFileService,
                                     ChatFileRepository chatFileRepository,
                                     CsvChatParser csvParser,
                                     MessageRepository messageRepository,
                                     ParseErrorRepository parseErrorRepository,
                                     ConversationSessionBuilder sessionBuilder,
                                     ConversationSessionRepository sessionRepository,
                                     SessionMessageRepository sessionMessageRepository,
                                     ProcessingJobService jobService,
                                     ProcessingJobExecutor jobExecutor,
                                     RelationshipAgentProperties properties,
                                     ImportBatchWriter importBatchWriter,
                                     DerivedDataPurger derivedDataPurger,
                                     StatisticsService statisticsService,
                                     AnalysisInvalidationService analysisInvalidationService,
                                     MemoryPersonaInvalidationService memoryPersonaInvalidationService) {
        this.chatFileService = chatFileService;
        this.chatFileRepository = chatFileRepository;
        this.csvParser = csvParser;
        this.messageRepository = messageRepository;
        this.parseErrorRepository = parseErrorRepository;
        this.sessionBuilder = sessionBuilder;
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.jobService = jobService;
        this.jobExecutor = jobExecutor;
        this.properties = properties;
        this.importBatchWriter = importBatchWriter;
        this.derivedDataPurger = derivedDataPurger;
        this.statisticsService = statisticsService;
        this.analysisInvalidationService = analysisInvalidationService;
        this.memoryPersonaInvalidationService = memoryPersonaInvalidationService;
    }

    public String startParse(String chatFileId, String selfParticipant, String targetParticipant) {
        // 输入哈希把源文件和解析器版本绑定起来；相同输入成功后直接幂等跳过。
        ChatFile chatFile = chatFileService.getById(chatFileId);
        String inputHash = ProcessingJobService.hashInput(chatFile.getSourceSha256(), CsvChatParser.PARSER_VERSION);
        ProcessingJob job = jobService.createOrGet(chatFileId, ProcessingJob.TYPE_PARSE, inputHash);
        if (job == null) throw new BizException(ErrorCode.IDEMPOTENT_SKIP);
        if (!jobService.tryTakeover(job.getId())) throw new BizException(ErrorCode.JOB_ALREADY_RUNNING);
        chatFileService.updateStatus(chatFileId, ChatFile.STATUS_PARSING);
        jobExecutor.submit("parse-" + chatFileId, () -> doParse(chatFile, selfParticipant, targetParticipant, job));
        return job.getId();
    }

    public String startSessionize(String chatFileId) {
        ChatFile chatFile = chatFileService.getById(chatFileId);
        String inputHash = ProcessingJobService.hashInput(chatFile.getSourceSha256(), SESSIONIZER_VERSION);
        ProcessingJob job = jobService.createOrGet(chatFileId, ProcessingJob.TYPE_SESSIONIZE, inputHash);
        if (job == null) throw new BizException(ErrorCode.IDEMPOTENT_SKIP);
        if (!jobService.tryTakeover(job.getId())) throw new BizException(ErrorCode.JOB_ALREADY_RUNNING);
        chatFileService.updateStatus(chatFileId, ChatFile.STATUS_SESSIONIZING);
        jobExecutor.submit("sessionize-" + chatFileId, () -> doSessionize(chatFile, job));
        return job.getId();
    }

    public JobStatusResponse getStatus(String chatFileId) {
        ChatFile chatFile = chatFileService.getById(chatFileId);
        List<ProcessingJob> jobs = jobService.listByChatFile(chatFileId);
        ProcessingJob currentJob = jobs.stream()
                .filter(j -> ProcessingJob.STATUS_RUNNING.equals(j.getStatus()))
                .findFirst().orElse(null);
        List<String> retryable = jobs.stream()
                .filter(j -> ProcessingJob.STATUS_FAILED.equals(j.getStatus()))
                .map(ProcessingJob::getJobType).toList();
        Integer cur = currentJob != null ? currentJob.getProgressCurrent() : null;
        Integer tot = currentJob != null ? currentJob.getProgressTotal() : null;
        return new JobStatusResponse(chatFile.getStatus(),
                currentJob != null ? currentJob.getJobType() : null,
                cur != null ? cur : 0,
                tot != null ? tot : 0, retryable);
    }

    /**
     * 整清重跑:删除该 chat_file 的全部派生产物(M2.1/M2.4 复用)。
     * 会话 → 消息 → 解析异常 → 统计缓存(CASCADE 自动清 session_message/retrieval_chunk/message_media)。
     */
    public void purgeDerivedData(String chatFileId) {
        derivedDataPurger.purgeAll(chatFileId);
    }

    private void doParse(ChatFile chatFile, String selfParticipant, String targetParticipant, ProcessingJob job) {
        try {
            // 解析是上游重建边界：先使下游分析、记忆和 Persona 失效，再清理派生表，保证不会混用旧数据。
            // M2.1: 整清重跑——解析前先删除该 chat_file 的全部产物,再从头执行
            analysisInvalidationService.supersedeForUpstreamRebuild(chatFile.getId(), ProcessingJob.TYPE_PARSE);
            memoryPersonaInvalidationService.supersedeForSourceRebuild(chatFile.getId(), ProcessingJob.TYPE_PARSE);
            purgeDerivedData(chatFile.getId());
            // 阶段2 M2.5: PARSE 重跑 → 下游 SESSIONIZE/CHUNK/EMBED job 回 PENDING
            jobService.resetToPending(chatFile.getId(), ProcessingJob.TYPE_SESSIONIZE,
                    ProcessingJob.TYPE_CHUNK, ProcessingJob.TYPE_EMBED);

            ParseResult result = csvParser.parse(chatFile, selfParticipant, targetParticipant);
            int batchSize = properties.job().importBatchSize();
            List<ParsedMessage> parsedMessages = result.messages();
            int total = parsedMessages.size();
            for (int i = 0; i < total; i += batchSize) {
                int end = Math.min(i + batchSize, total);
                importBatchWriter.saveBatch(chatFile.getId(), parsedMessages.subList(i, end));
                jobService.updateProgress(job.getId(), end, total);
            }
            for (ParseError err : result.errors()) parseErrorRepository.insert(err);
            long count = messageRepository.selectCount(new LambdaQueryWrapper<Message>()
                    .eq(Message::getChatFileId, chatFile.getId()));
            ChatFile update = new ChatFile();
            update.setId(chatFile.getId());
            update.setStatus(ChatFile.STATUS_PARSED);
            update.setMessageCount((int) count);
            chatFileRepository.updateById(update);
            jobService.markSuccess(job.getId());
            log.info("Parse completed: {} messages, {} errors", total, result.errors().size());
        } catch (Exception e) {
            log.error("Parse failed: chatFileId={}", chatFile.getId(), e);
            jobService.markFailed(job.getId(), e.getMessage());
            chatFileService.updateError(chatFile.getId(), e.getMessage());
        }
    }

    private void doSessionize(ChatFile chatFile, ProcessingJob job) {
        try {
            // M2.2: 整清重跑——切分前先删除会话与统计缓存(消息保留),CASCADE 清 session_message/retrieval_chunk
            analysisInvalidationService.supersedeForUpstreamRebuild(chatFile.getId(), ProcessingJob.TYPE_SESSIONIZE);
            memoryPersonaInvalidationService.supersedeForSourceRebuild(chatFile.getId(), ProcessingJob.TYPE_SESSIONIZE);
            derivedDataPurger.purgeSessionsAndStats(chatFile.getId());
            // 阶段2 M2.5: SESSIONIZE 重跑 → 下游 CHUNK/EMBED job 回 PENDING
            jobService.resetToPending(chatFile.getId(), ProcessingJob.TYPE_CHUNK, ProcessingJob.TYPE_EMBED);

            List<Message> messages = messageRepository.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getChatFileId, chatFile.getId())
                    .orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId));
            if (messages.isEmpty()) {
                jobService.markSuccess(job.getId());
                chatFileService.updateStatus(chatFile.getId(), ChatFile.STATUS_SESSIONIZED);
                return;
            }
            // M5: 一次性产出会话+消息,不再二次对齐
            SessionBuildResult buildResult = sessionBuilder.buildWithMessages(messages, chatFile.getSourceTimezone());
            for (SessionWithMessages swm : buildResult.sessions()) {
                swm.session().setChatFileId(chatFile.getId());
                sessionRepository.insert(swm.session());
                List<SessionMessage> sms = sessionBuilder.buildSessionMessages(swm.session(), swm.messages());
                for (SessionMessage sm : sms) sessionMessageRepository.insert(sm);
            }
            chatFileService.updateStatus(chatFile.getId(), ChatFile.STATUS_SESSIONIZED);
            jobService.markSuccess(job.getId());
            log.info("Sessionize completed: {} sessions", buildResult.sessions().size());
            // M6: 会话构建成功后自动刷新统计缓存(设计文档 4.4:唯一允许的自动级联);
            // 非阻塞——统计失败不影响已成功的会话结果,仅 warn 便于定位
            try {
                statisticsService.computeAndCache(chatFile.getId());
            } catch (Exception statsEx) {
                log.warn("Statistics compute failed (sessionize still succeeded): chatFileId={}",
                        chatFile.getId(), statsEx);
            }
        } catch (Exception e) {
            log.error("Sessionize failed: chatFileId={}", chatFile.getId(), e);
            jobService.markFailed(job.getId(), e.getMessage());
            chatFileService.updateError(chatFile.getId(), e.getMessage());
        }
    }
}
