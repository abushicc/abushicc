package com.example.relationshipagent.parser;

import java.util.List;

/**
 * 解析结果（设计文档 7.2.1）。
 */
public record ParseResult(
        List<ParsedMessage> messages,
        List<ParseError> errors
) {
}
