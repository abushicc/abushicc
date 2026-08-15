package com.example.relationshipagent.chatfile.service;

import com.example.relationshipagent.media.MessageMedia;
import com.example.relationshipagent.media.MessageMediaRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.parser.ParsedMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 批量写入器(M2.3):把 saveBatch 抽到独立 bean,确保 {@code @Transactional} 经由 Spring 代理生效。
 * <p>
 * 原实现中 {@code ChatFileProcessingService.doParse} 内部直接 this.saveBatch(...),
 * Spring AOP 不走代理,事务注解形同虚设,27K 条逐条自动提交。
 * 现事务粒度 = 每批({@code ra.job.import-batch-size},默认 1000)一个事务。
 * 结合整清重跑策略,半批次残留可被下次重跑的清理覆盖,无需 ON CONFLICT。
 * <p>
 * M4:媒体类型消息一律写入 message_media 行,source_ref 为反查到的真实路径或 NULL(未命中)。
 */
@Service
public class ImportBatchWriter {

    private final MessageRepository messageRepository;
    private final MessageMediaRepository messageMediaRepository;

    public ImportBatchWriter(MessageRepository messageRepository,
                             MessageMediaRepository messageMediaRepository) {
        this.messageRepository = messageRepository;
        this.messageMediaRepository = messageMediaRepository;
    }

    @Transactional
    public void saveBatch(String chatFileId, List<ParsedMessage> parsedMessages) {
        Instant now = Instant.now();
        for (ParsedMessage pm : parsedMessages) {
            Message msg = new Message();
            msg.setId(UUID.randomUUID().toString());
            msg.setChatFileId(chatFileId);
            msg.setSpeaker(pm.speaker());
            msg.setContent(pm.content());
            msg.setCleanedContent(pm.cleanedContent());
            msg.setMessageTime(pm.messageTime());
            msg.setMessageType(pm.messageType().name());
            msg.setSourceLocalId(pm.sourceLocalId());
            msg.setSourceLineNo(pm.sourceLineNo());
            msg.setCreatedAt(now);
            messageRepository.insert(msg);

            // M4: 媒体消息一律建行,source_ref 可空(NULL 表示未关联到真实文件)
            if (pm.messageType().isMedia()) {
                MessageMedia media = new MessageMedia();
                media.setId(UUID.randomUUID().toString());
                media.setMessageId(msg.getId());
                media.setMediaType(pm.messageType().name());
                media.setSourceRef(pm.mediaSourceRef());
                media.setExtractionStatus(MessageMedia.STATUS_NOT_REQUESTED);
                media.setCreatedAt(now);
                messageMediaRepository.insert(media);
            }
        }
    }
}
