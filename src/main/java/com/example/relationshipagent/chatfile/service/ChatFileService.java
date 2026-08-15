package com.example.relationshipagent.chatfile.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.parser.CsvChatParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ChatFileService {

    private static final Logger log = LoggerFactory.getLogger(ChatFileService.class);

    private final ChatFileRepository chatFileRepository;
    private final RelationshipAgentProperties properties;
    private final DerivedDataPurger derivedDataPurger;

    @Value("${chat-history.root-dir:}")
    private String chatHistoryRootDir;

    public ChatFileService(ChatFileRepository chatFileRepository,
                           RelationshipAgentProperties properties,
                           DerivedDataPurger derivedDataPurger) {
        this.chatFileRepository = chatFileRepository;
        this.properties = properties;
        this.derivedDataPurger = derivedDataPurger;
    }

    @Transactional
    public ChatFile uploadAndCreate(MultipartFile file, String selfParticipant,
                                    String targetParticipant, String sourceTimezone)
            throws Exception {

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        String safeName = Paths.get(originalName).getFileName().toString();
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("仅支持 CSV 文件");
        }

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ra-uploads");
        Files.createDirectories(tempDir);
        Path tempFile = tempDir.resolve(UUID.randomUUID() + "_" + safeName);
        file.transferTo(tempFile.toFile());

        String sha256 = sha256(tempFile.toFile());

        ChatFile existing = chatFileRepository.selectOne(new LambdaQueryWrapper<ChatFile>()
                .eq(ChatFile::getSourceSha256, sha256)
                .eq(ChatFile::getParserVersion, CsvChatParser.PARSER_VERSION));

        if (existing != null) {
            Files.deleteIfExists(tempFile);
            if (ChatFile.STATUS_READY.equals(existing.getStatus())) {
                throw new BizException(ErrorCode.FILE_ALREADY_IMPORTED, "文件已导入完成");
            }
            if (ChatFile.STATUS_ERROR.equals(existing.getStatus())) {
                // M2.4: 重置为 UPLOADED 前补清派生产物(会话→消息→解析异常→统计缓存),以便整清重跑
                derivedDataPurger.purgeAll(existing.getId());
                existing.setStatus(ChatFile.STATUS_UPLOADED);
                existing.setErrorMessage(null);
                chatFileRepository.updateById(existing);
                return existing;
            }
            Files.deleteIfExists(tempFile);
            return existing;
        }

        String storageDirName = sha256.substring(0, 2) + "/" + sha256.substring(2, 4);
        Path storageDir = Paths.get(chatHistoryRootDir, "imported", storageDirName);
        Files.createDirectories(storageDir);
        Path storedFile = storageDir.resolve(safeName).normalize();
        if (!storedFile.startsWith(storageDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("文件名超出存储目录");
        }
        Files.move(tempFile, storedFile, StandardCopyOption.REPLACE_EXISTING);

        ChatFile chatFile = new ChatFile();
        chatFile.setId(UUID.randomUUID().toString());
        chatFile.setFileName(safeName);
        chatFile.setSourceSha256(sha256);
        chatFile.setSourceFormat("CSV");
        chatFile.setFilePath(storedFile.toAbsolutePath().toString());
        chatFile.setEncoding("GB18030");
        chatFile.setSourceTimezone(sourceTimezone != null ? sourceTimezone : "Asia/Shanghai");
        chatFile.setParserVersion(CsvChatParser.PARSER_VERSION);
        chatFile.setStatus(ChatFile.STATUS_UPLOADED);
        chatFile.setUploadedAt(Instant.now());
        chatFileRepository.insert(chatFile);

        log.info("ChatFile created: id={}, fileName={}", chatFile.getId(), chatFile.getFileName());
        return chatFile;
    }

    @Transactional
    public void delete(String id) throws Exception {
        ChatFile file = getById(id);
        Path stored = Paths.get(file.getFilePath()).toAbsolutePath().normalize();
        Path importedRoot = Paths.get(chatHistoryRootDir, "imported").toAbsolutePath().normalize();
        if (!stored.startsWith(importedRoot)) {
            throw new IllegalStateException("拒绝删除存储目录之外的文件");
        }
        Files.deleteIfExists(stored);
        derivedDataPurger.purgeAll(id);
        chatFileRepository.deleteById(id);
    }

    public ChatFile getById(String id) {
        ChatFile file = chatFileRepository.selectById(id);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        return file;
    }

    public void updateStatus(String id, String status) {
        ChatFile file = new ChatFile();
        file.setId(id);
        file.setStatus(status);
        chatFileRepository.updateById(file);
    }

    public void updateError(String id, String errorMessage) {
        ChatFile file = new ChatFile();
        file.setId(id);
        file.setStatus(ChatFile.STATUS_ERROR);
        file.setErrorMessage(errorMessage);
        chatFileRepository.updateById(file);
    }

    private String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
