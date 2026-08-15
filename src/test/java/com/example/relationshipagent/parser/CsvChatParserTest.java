package com.example.relationshipagent.parser;

import com.example.relationshipagent.chatfile.model.ChatFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvChatParserTest {

    private CsvChatParser parser;
    private ChatFile chatFile;

    @BeforeEach
    void setUp() {
        parser = new CsvChatParser(new MediaFileIndex("./src/test/resources/fixtures"));
        chatFile = new ChatFile();
        chatFile.setId(UUID.randomUUID().toString());
        chatFile.setSourceFormat("CSV");
        chatFile.setEncoding("UTF-8");
        URL fixtureUrl = getClass().getClassLoader().getResource("fixtures/test_chat.csv");
        assertThat(fixtureUrl).isNotNull();
        chatFile.setFilePath(new File(fixtureUrl.getFile()).getAbsolutePath());
    }

    @Test
    @DisplayName("CSV 基本解析")
    void shouldParseTextMessages() {
        ParseResult result = parser.parse(chatFile, "kiwi", "耳朵小");
        assertThat(result.messages()).hasSize(10);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("说话人解析")
    void shouldResolveSpeakers() {
        ParseResult result = parser.parse(chatFile, "kiwi", "耳朵小");
        assertThat(result.messages().get(0).speaker()).isEqualTo("耳朵小");
        assertThat(result.messages().get(1).speaker()).isEqualTo("kiwi");
    }

    @Test
    @DisplayName("表情包消息")
    void shouldParseEmojiMessage() {
        ParseResult result = parser.parse(chatFile, "kiwi", "耳朵小");
        ParsedMessage emoji = result.messages().get(5);
        assertThat(emoji.messageType()).isEqualTo(MessageType.EMOJI);
        assertThat(emoji.cleanedContent()).isEqualTo("[呲牙]");
    }

    @Test
    @DisplayName("HTML 转义还原")
    void shouldUnescapeHtml() {
        ParseResult result = parser.parse(chatFile, "kiwi", "耳朵小");
        ParsedMessage htmlMsg = result.messages().get(7);
        assertThat(htmlMsg.cleanedContent()).contains("是啊").doesNotContain("&amp;");
    }

    @Test
    @DisplayName("时间解析")
    void shouldParseTimestamps() {
        ParseResult result = parser.parse(chatFile, "kiwi", "耳朵小");
        assertThat(result.messages().get(0).messageTime().getEpochSecond()).isEqualTo(1610008864);
    }

    @Test
    @DisplayName("未知 NickName — 仍按 IsSender 分配说话人")
    void shouldStillAssignSpeakerByIsSender() {
        // 修改后不再因 NickName 不匹配而拒绝, 仅按 IsSender 分配
        ParseResult result = parser.parse(chatFile, "wrong_name", "also_wrong");
        assertThat(result.messages()).isNotEmpty();
        assertThat(result.messages().get(0).speaker()).isEqualTo("also_wrong"); // IsSender=0
        assertThat(result.messages().get(1).speaker()).isEqualTo("wrong_name"); // IsSender=1
    }

    @Test
    @DisplayName("目标人物参数乱码时回退到 GB18030 CSV NickName")
    void shouldFallbackToDecodedNicknameWhenTargetParameterIsMojibake() {
        ChatFile gb = gb18030ChatFile();

        ParseResult result = parser.parse(gb, "kiwi", "����С");

        assertThat(result.errors()).isEmpty();
        assertThat(result.messages().get(0).speaker()).isEqualTo("耳朵小");
        assertThat(result.messages().get(1).speaker()).isEqualTo("kiwi");
        assertThat(result.messages()).extracting(ParsedMessage::speaker)
                .doesNotContain("����С");
    }

    @Test
    @DisplayName("参与者参数和 CSV 名称都不可用时拒绝写入 speaker")
    void shouldRejectRecordWhenNoUsableParticipantNameExists() throws Exception {
        Path path = Files.createTempFile("invalid-participant", ".csv");
        Files.writeString(path, "LocalId\tTalkerId\tType\tSubType\tIsSender\tCreateTime\tStatus\tStrContent\tStrTime\tRemark\tNickName\tSender\n"
                + "1\tx\t1\t0\t0\t1610008864\t0\thello\t\t\t\t\n");
        ChatFile invalid = new ChatFile();
        invalid.setId(UUID.randomUUID().toString());
        invalid.setSourceFormat("CSV");
        invalid.setEncoding("UTF-8");
        invalid.setFilePath(path.toString());

        ParseResult result = parser.parse(invalid, "kiwi", "����");

        assertThat(result.messages()).isEmpty();
        assertThat(result.errors()).extracting(ParseError::getErrorType)
                .containsExactly(ParseError.ERR_SPEAKER_UNKNOWN);
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("文件不存在")
    void shouldHandleMissingFile() {
        ChatFile missing = new ChatFile();
        missing.setId(UUID.randomUUID().toString());
        missing.setSourceFormat("CSV");
        missing.setFilePath("/nonexistent/file.csv");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(missing, "kiwi", "耳朵小"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CSV file not found");
    }

    @Test
    @DisplayName("非法 IsSender 记录解析异常，不默认归属对方")
    void shouldRejectInvalidIsSender() throws Exception {
        Path path = Files.createTempFile("invalid-sender", ".csv");
        Files.writeString(path, "LocalId\tTalkerId\tType\tSubType\tIsSender\tCreateTime\tStatus\tStrContent\tStrTime\tRemark\tNickName\tSender\n"
                + "1\tx\t1\t0\t2\t1610008864\t0\thello\t\t\t\t\n");
        ChatFile invalid = new ChatFile();
        invalid.setId(UUID.randomUUID().toString());
        invalid.setSourceFormat("CSV");
        invalid.setEncoding("UTF-8");
        invalid.setFilePath(path.toString());
        ParseResult result = parser.parse(invalid, "kiwi", "耳朵小");
        assertThat(result.messages()).isEmpty();
        assertThat(result.errors()).extracting(ParseError::getErrorType)
                .contains(ParseError.ERR_SPEAKER_UNKNOWN);
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("非法 LocalId 记录解析异常，不写入 0")
    void shouldRejectInvalidLocalId() throws Exception {
        Path path = Files.createTempFile("invalid-local-id", ".csv");
        Files.writeString(path, "LocalId\tTalkerId\tType\tSubType\tIsSender\tCreateTime\tStatus\tStrContent\tStrTime\tRemark\tNickName\tSender\n"
                + "bad\tx\t1\t0\t1\t1610008864\t0\thello\t\t\t\t\n");
        ChatFile invalid = new ChatFile();
        invalid.setId(UUID.randomUUID().toString());
        invalid.setSourceFormat("CSV");
        invalid.setEncoding("UTF-8");
        invalid.setFilePath(path.toString());
        ParseResult result = parser.parse(invalid, "kiwi", "耳朵小");
        assertThat(result.messages()).isEmpty();
        assertThat(result.errors()).extracting(ParseError::getErrorType)
                .contains(ParseError.ERR_FORMAT);
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("GB18030 编码解码（设计文档 17.10）")
    void shouldParseGb18030EncodedFile() {
        ChatFile gb = gb18030ChatFile();

        ParseResult result = parser.parse(gb, "kiwi", "耳朵小");
        assertThat(result.messages()).hasSize(10);
        assertThat(result.errors()).isEmpty();
        // GB18030 正确解码中文(若编码错误会乱码或报错)
        assertThat(result.messages().get(0).cleanedContent()).contains("你好");
        assertThat(result.messages().get(0).speaker()).isEqualTo("耳朵小");
    }

    private ChatFile gb18030ChatFile() {
        ChatFile gb = new ChatFile();
        gb.setId(UUID.randomUUID().toString());
        gb.setSourceFormat("CSV");
        gb.setEncoding("GB18030");
        gb.setSourceTimezone("Asia/Shanghai");
        URL url = getClass().getClassLoader().getResource("fixtures/test_chat_gb18030.csv");
        assertThat(url).isNotNull();
        gb.setFilePath(new File(url.getFile()).getAbsolutePath());
        return gb;
    }
}
