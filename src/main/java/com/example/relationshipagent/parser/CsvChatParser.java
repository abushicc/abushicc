package com.example.relationshipagent.parser;

import com.example.relationshipagent.chatfile.model.ChatFile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 CSV 聊天记录解析器（设计文档 7.2 / 17.5）。
 * <p>
 * 使用 Apache Commons CSV（TDF Tab 分隔），GB18030 编码。
 * 禁止 {@code split("\\t")} — Commons CSV 正确处理引号内嵌的换行与 Tab。
 */
@Component
public class CsvChatParser implements ChatParser {

    private static final Logger log = LoggerFactory.getLogger(CsvChatParser.class);

    /**
     * 表情包 XML 中提取描述的正则。
     * 示例: {@code <emoji desc="[呲牙]" ...>}
     */
    private static final Pattern EMOJI_DESC_PATTERN =
            Pattern.compile("desc=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /**
     * 系统消息 XML 中提取可读文本的正则。
     */
    private static final Pattern SYS_CONTENT_PATTERN =
            Pattern.compile("<content>([^<]*)</content>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE_MSG_PATTERN =
            Pattern.compile("<replacemsg><!\\[CDATA\\[([^\\]]*)\\]\\]></replacemsg>", Pattern.CASE_INSENSITIVE);

    /**
     * 列索引（WeChat 导出固定顺序）
     */
    private static final int COL_LOCAL_ID = 0;
    private static final int COL_TALKER_ID = 1;
    private static final int COL_TYPE = 2;
    private static final int COL_SUB_TYPE = 3;
    private static final int COL_IS_SENDER = 4;
    private static final int COL_CREATE_TIME = 5;
    private static final int COL_STATUS = 6;
    private static final int COL_STR_CONTENT = 7;
    private static final int COL_STR_TIME = 8;
    private static final int COL_REMARK = 9;
    private static final int COL_NICK_NAME = 10;
    private static final int COL_SENDER = 11;

    // Parser version for idempotent import
    public static final String PARSER_VERSION = "v1.0.0";

    /**
     * 媒体文件反查索引(M4):按时间戳前缀反查真实路径
     */
    private final MediaFileIndex mediaFileIndex;

    public CsvChatParser(MediaFileIndex mediaFileIndex) {
        this.mediaFileIndex = mediaFileIndex;
    }

    @Override
    public boolean supports(ChatFile file) {
        return "CSV".equalsIgnoreCase(file.getSourceFormat());
    }

    @Override
    public ParseResult parse(ChatFile file, String selfParticipant, String targetParticipant) {
        List<ParsedMessage> messages = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();

        File csvFile = new File(file.getFilePath());
        if (!csvFile.exists()) {
            log.error("CSV file not found: {}", file.getFilePath());
            throw new IllegalStateException("CSV file not found: " + file.getFilePath());
        }

        // M4: 媒体反查以 chat_file.source_timezone 为准(默认 Asia/Shanghai),不用系统默认时区
        ZoneId zoneId = ZoneId.of(file.getSourceTimezone() != null ? file.getSourceTimezone() : "Asia/Shanghai");
        SpeakerResolutionContext speakerContext = new SpeakerResolutionContext(selfParticipant, targetParticipant);

        CSVFormat format = CSVFormat.TDF.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false)
                .setAllowMissingColumnNames(true)
                .build();

        try (Reader reader = new InputStreamReader(
                new BufferedInputStream(new FileInputStream(csvFile)),
                Charset.forName(file.getEncoding() != null ? file.getEncoding() : "GB18030"));
             CSVParser parser = format.parse(reader)) {

            int lineNo = 1; // CSV 首行为表头，数据从第 2 行开始
            for (var record : parser) {
                lineNo++;
                try {
                    if (record.size() < 12) {
                        // 列数不足，视为格式异常
                        errors.add(makeError(file.getId(), lineNo, record.toString(),
                                ParseError.ERR_FORMAT, "列数不足: " + record.size()));
                        continue;
                    }
                    processRecord(record, lineNo, file, zoneId, speakerContext,
                            messages, errors);
                } catch (Exception e) {
                    log.debug("Parse error at line {}: {}", lineNo, e.getMessage());
                    errors.add(makeError(file.getId(), lineNo, record.toString(),
                            ParseError.ERR_FORMAT, e.getMessage()));
                }
            }
            log.info("CSV parsed: {} messages, {} errors from {}", messages.size(), errors.size(),
                    file.getFilePath());
        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", file.getFilePath(), e);
            errors.add(makeError(file.getId(), 0, null, ParseError.ERR_FORMAT,
                    "文件读取失败: " + e.getMessage()));
        }

        logMediaMatchRates(messages);
        return new ParseResult(messages, errors);
    }

    /**
     * 输出各类型媒体关联成功率(M4)。
     * 预期:IMAGE ≈ 609/609、VOICE ≈ 16/16、VIDEO ≈ 24/24、FILE 0/17 置空。
     */
    private void logMediaMatchRates(List<ParsedMessage> messages) {
        Map<MessageType, int[]> stats = new EnumMap<>(MessageType.class);
        for (ParsedMessage pm : messages) {
            if (!pm.messageType().isMedia()) continue;
            int[] c = stats.computeIfAbsent(pm.messageType(), k -> new int[2]);
            c[0]++;
            if (pm.mediaSourceRef() != null) c[1]++;
        }
        for (Map.Entry<MessageType, int[]> e : stats.entrySet()) {
            int[] c = e.getValue();
            log.info("Media match rate: {} = {}/{}", e.getKey(), c[1], c[0]);
        }
    }

    private void processRecord(org.apache.commons.csv.CSVRecord record, int lineNo,
                               ChatFile file, ZoneId zoneId, SpeakerResolutionContext speakerContext,
                               List<ParsedMessage> messages, List<ParseError> errors) {

        // 1. 解析 CreateTime
        String createTimeStr = getCol(record, COL_CREATE_TIME);
        long unixSeconds;
        try {
            unixSeconds = Long.parseLong(createTimeStr);
        } catch (NumberFormatException e) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_BAD_TIMESTAMP, "CreateTime 非数字: " + createTimeStr));
            return;
        }

        if (unixSeconds <= 0) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_BAD_TIMESTAMP, "CreateTime <= 0: " + unixSeconds));
            return;
        }

        // 防止明显未来时间（> 入库时间 + 1 天）
        Instant messageTime = Instant.ofEpochSecond(unixSeconds);
        if (messageTime.isAfter(Instant.now().plusSeconds(86400))) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_BAD_TIMESTAMP, "CreateTime 为未来时间: " + createTimeStr));
            return;
        }

        // 2. 解析 Type → MessageType
        String typeStr = getCol(record, COL_TYPE);
        int typeCode;
        boolean typeUnknown = false;
        try {
            typeCode = Integer.parseInt(typeStr);
        } catch (NumberFormatException e) {
            typeCode = 1; // 默认 TEXT
            typeUnknown = true;
        }
        MessageType messageType = MessageType.fromCode(typeCode);
        if (!typeUnknown && messageType == MessageType.TEXT && typeCode != 1) {
            // 未知 Type code，按 TEXT 处理并记录
            typeUnknown = true;
        }
        if (typeUnknown) {
            log.debug("Line {}: unknown message type code {}", lineNo, typeCode);
        }

        // 3. 解析说话人
        String isSenderStr = getCol(record, COL_IS_SENDER);
        String remark = getCol(record, COL_REMARK);
        String nickName = getCol(record, COL_NICK_NAME);
        String speaker = resolveSpeaker(isSenderStr, nickName, remark, speakerContext);
        if (speaker == null) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_SPEAKER_UNKNOWN,
                    "无法确定说话人: IsSender=" + isSenderStr + ", NickName=" + nickName));
            return;
        }

        // 4. 解析 LocalId
        String localIdStr = getCol(record, COL_LOCAL_ID);
        long sourceLocalId;
        try {
            sourceLocalId = Long.parseLong(localIdStr);
        } catch (NumberFormatException e) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_FORMAT, "LocalId 非数字: " + localIdStr));
            return;
        }

        // 5. 解析内容并清洗
        String rawContent = getCol(record, COL_STR_CONTENT);
        String cleanedContent = cleanContent(rawContent, messageType);
        String mediaSourceRef = null;

        // 文字消息内容为空 → 记录异常，但非文本消息允许空内容
        if (messageType.isTextual() && (rawContent == null || rawContent.isBlank())) {
            errors.add(makeError(file.getId(), lineNo, record.toString(),
                    ParseError.ERR_EMPTY_CONTENT,
                    "文字消息内容为空，speaker=" + speaker));
            return;
        }

        // M4: 媒体消息按时间戳前缀反查真实路径;未命中置 null(message_media 行照常写入,source_ref 为 NULL)
        if (messageType.isMedia()) {
            Optional<String> resolved = mediaFileIndex.resolve(messageType, messageTime, zoneId);
            mediaSourceRef = resolved.orElse(null);
        }

        messages.add(new ParsedMessage(
                speaker, rawContent, cleanedContent,
                messageTime, messageType, sourceLocalId, lineNo,
                mediaSourceRef));
    }

    /**
     * 按消息类型清洗内容（设计文档 7.2.2 / 17.5）。
     */
    private String cleanContent(String raw, MessageType type) {
        return switch (type) {
            case TEXT -> cleanTextContent(raw);
            case IMAGE -> "[图片]";
            case VOICE -> "[语音]";
            case VIDEO -> "[视频]";
            case EMOJI -> cleanEmojiContent(raw);
            case LOCATION -> "[位置]";
            case SYSTEM -> cleanSystemContent(raw);
            case FILE -> "[文件]";
            case SYS_NOTICE -> "[系统消息]";
            case MERGED_HISTORY -> "[聊天记录]";
        };
    }

    /**
     * 清洗文字消息：还原 HTML 转义、压缩连续空白。
     */
    private String cleanTextContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String cleaned = raw
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'");
        // 压缩连续空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    /**
     * 清洗表情包：尝试从 XML 中提取描述，失败则返回 [表情包]。
     */
    private String cleanEmojiContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[表情包]";
        }
        Matcher m = EMOJI_DESC_PATTERN.matcher(raw);
        if (m.find()) {
            String desc = m.group(1);
            // 描述可能已自带方括号，去掉后统一格式
            desc = desc.replaceAll("^\\[", "").replaceAll("\\]$", "");
            return "[" + desc + "]";
        }
        return "[表情包]";
    }

    /**
     * 清洗系统消息：提取可读文本，前缀 [系统提示: …]。
     */
    private String cleanSystemContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[系统提示]";
        }
        // 尝试提取 replacemsg 中的文本
        Matcher rm = REPLACE_MSG_PATTERN.matcher(raw);
        if (rm.find()) {
            String msg = rm.group(1).trim();
            if (!msg.isEmpty()) {
                return "[系统提示: " + msg + "]";
            }
        }
        // 尝试提取 content 中的文本
        Matcher cm = SYS_CONTENT_PATTERN.matcher(raw);
        if (cm.find()) {
            String msg = cm.group(1).trim();
            if (!msg.isEmpty()) {
                return "[系统提示: " + msg + "]";
            }
        }
        return "[系统提示]";
    }

    /**
     * 根据 IsSender 确定身份方向，再解析可安全入库的显示名。
     * <p>
     * 合法的显式参与者参数仍是主契约；参数出现 Unicode 替换字符等明确乱码时，
     * 回退到已经按 chat_file.encoding 解码的 CSV NickName/Remark。这样既能修复终端
     * multipart 编码损坏，又不会改变群聊等场景中“显式指定目标人物”的既有语义。
     */
    private String resolveSpeaker(String isSenderStr, String nickName, String remark,
                                  SpeakerResolutionContext context) {
        if (isSenderStr == null) return null;

        String normalized = isSenderStr.trim();
        if ("1".equals(normalized)) {
            return resolveParticipant("self", context.selfParticipant, nickName, remark, context);
        } else if ("0".equals(normalized)) {
            return resolveParticipant("target", context.targetParticipant, nickName, remark, context);
        }
        return null;
    }

    private String resolveParticipant(String role, String explicitParticipant, String nickName, String remark,
                                      SpeakerResolutionContext context) {
        String explicit = usableParticipant(explicitParticipant);
        if (explicit != null) {
            String csvName = usableParticipant(nickName);
            if (csvName != null && !csvName.equals(explicit)) {
                log.debug("CSV NickName={} differs from explicit {}Participant={} (using explicit parameter)",
                        csvName, role, explicit);
            }
            return explicit;
        }

        String fallback = usableParticipant(nickName);
        String source = "NickName";
        if (fallback == null) {
            fallback = usableParticipant(remark);
            source = "Remark";
        }
        if (fallback == null) {
            return null;
        }

        if (context.markFallbackWarned(role)) {
            log.warn("Malformed or empty {}Participant; falling back to decoded CSV {}={}",
                    role, source, fallback);
        }
        return fallback;
    }

    /**
     * U+FFFD 表示字节曾用错误字符集解码；控制字符同样不允许进入 speaker。
     */
    private String usableParticipant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.indexOf('\uFFFD') >= 0) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                return null;
            }
        }
        return normalized;
    }

    private static final class SpeakerResolutionContext {
        private final String selfParticipant;
        private final String targetParticipant;
        private boolean selfFallbackWarned;
        private boolean targetFallbackWarned;

        private SpeakerResolutionContext(String selfParticipant, String targetParticipant) {
            this.selfParticipant = selfParticipant;
            this.targetParticipant = targetParticipant;
        }

        /**
         * 返回 true 仅用于第一次 warning，避免每条消息重复记录同一上传参数问题。
         */
        private boolean markFallbackWarned(String role) {
            if ("self".equals(role)) {
                if (selfFallbackWarned) return false;
                selfFallbackWarned = true;
                return true;
            }
            if (targetFallbackWarned) return false;
            targetFallbackWarned = true;
            return true;
        }
    }

    private String getCol(org.apache.commons.csv.CSVRecord record, int index) {
        if (index >= record.size()) {
            return "";
        }
        String val = record.get(index);
        return val != null ? val.trim() : "";
    }

    private ParseError makeError(String chatFileId, int lineNo, String rawContent,
                                 String errorType, String message) {
        ParseError err = new ParseError();
        err.setId(UUID.randomUUID().toString());
        err.setChatFileId(chatFileId);
        err.setSourceLineNo(lineNo);
        err.setRawContent(rawContent != null && rawContent.length() > 1000
                ? rawContent.substring(0, 1000) : rawContent);
        err.setErrorType(errorType);
        err.setErrorMessage(message);
        err.setCreatedAt(Instant.now());
        return err;
    }
}
