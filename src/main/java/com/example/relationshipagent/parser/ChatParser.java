package com.example.relationshipagent.parser;

import com.example.relationshipagent.chatfile.model.ChatFile;

import java.util.List;

/**
 * 聊天记录解析器接口（设计文档 7.2.1）。
 */
public interface ChatParser {

    /**
     * 判断是否支持解析该文件。
     */
    boolean supports(ChatFile file);

    /**
     * 解析聊天文件，返回标准化消息和异常行列表。
     *
     * @param file              聊天文件
     * @param selfParticipant   自己的昵称（如 "kiwi"）
     * @param targetParticipant 对方的昵称（如 "耳朵小"）
     * @return 解析结果
     */
    ParseResult parse(ChatFile file, String selfParticipant, String targetParticipant);
}
