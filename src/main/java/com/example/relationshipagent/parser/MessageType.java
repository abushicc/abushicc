package com.example.relationshipagent.parser;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 消息类型枚举（设计文档 7.2.2 / 17.5）。
 * WeChat 导出 CSV 中的 Type 字段值。
 */
public enum MessageType {

    TEXT(1, "文字消息"),
    IMAGE(3, "图片"),
    VOICE(34, "语音"),
    VIDEO(43, "视频"),
    EMOJI(47, "表情包"),
    LOCATION(48, "位置"),
    SYSTEM(49, "系统提示"),
    FILE(50, "文件"),
    SYS_NOTICE(10000, "系统消息（红包/转账）"),
    MERGED_HISTORY(11000, "聊天记录合并转发");

    private final int code;
    private final String description;

    private static final Map<Integer, MessageType> CODE_MAP =
            Stream.of(values()).collect(Collectors.toMap(MessageType::code, t -> t));

    MessageType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    /**
     * 根据 CSV Type 值返回枚举。
     * 未知 code 返回 TEXT 并应由调用方记录 warning。
     */
    public static MessageType fromCode(int code) {
        return CODE_MAP.getOrDefault(code, TEXT);
    }

    /**
     * 返回该类型是否为文本语义类型（适合 LLM 阅读）。
     */
    public boolean isTextual() {
        return this == TEXT || this == SYSTEM || this == SYS_NOTICE;
    }

    /**
     * 返回该类型是否代表媒体内容。
     */
    public boolean isMedia() {
        return this == IMAGE || this == VOICE || this == VIDEO || this == FILE;
    }
}
