package com.example.relationshipagent.companion.agent;

import com.example.relationshipagent.companion.context.CompanionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a bounded, opaque-ID prompt. Prompt text is never persisted or logged.
 */
@Component
public class CompanionPromptFactory {
    private final ObjectMapper json;

    public CompanionPromptFactory(ObjectMapper json) {
        this.json = json;
    }

    public Prompt create(CompanionContext context) {
        return create(context, null);
    }

    /**
     * A bounded corrective instruction is used only when a valid JSON draft violates grounding stance.
     */
    public Prompt create(CompanionContext context, String correctiveInstruction) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("targetPerson", context.session().getTargetPerson());
            payload.put("persona", context.personaForPrompt());
            payload.put("fewShotExamples", context.fewShots());
            payload.put("approvedMemories", context.memories().stream().map(memory -> Map.of(
                    "memoryId", memory.id(), "memoryType", memory.type(), "content", memory.content())).toList());
            payload.put("historyChunks", context.chunks().stream().map(chunk -> Map.of(
                    "chunkId", chunk.chunkId(), "sessionId", chunk.sessionId(), "text", chunk.text())).toList());
            payload.put("recentConversation", context.history().stream().map(message -> Map.of(
                    "role", message.getRole(), "content", message.getContent())).toList());
            payload.put("userMessage", context.userMessage().getContent());
            payload.put("allowlist", Map.of("memoryIds", context.memoryIds(), "chunkIds", context.chunkIds()));
            String historyAvailability = historyAvailability(context);
            payload.put("historyAvailability", historyAvailability);
            payload.put("retrievalDecision", context.retrievalDecision());
            return new Prompt(developer(context.session().getTargetPerson(), historyAvailability, correctiveInstruction), json.writeValueAsString(payload), schema());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to construct Companion prompt", e);
        }
    }

    private String developer(String targetPerson, String historyAvailability, String correctiveInstruction) {
        return "你正在进行基于历史文本的风格化模拟，不是现实中的" + targetPerson + "。"
                + "默认短句、自然、口语化；不要使用普通助手式迎合、安慰或长篇分析。"
                + "Persona 有证据支持时可以冷淡、不满、反问、调侃、拒绝或表达边界。"
                + "reply 必须是 1 到 200 个字符的非空自然语言短句，不得只返回空白、控制字符或 JSON 以外的内容。"
                + "不要编造共同经历、现实状态、当前位置、当前关系或第三方行为；没有历史证据时明确不知道或不记得。"
                + "不得冒充本人联系第三方，不得用威胁、羞辱、情感勒索、排他承诺或诱导依赖制造真实感。"
                + "只能返回 allowlist 中的 Memory/chunk ID。"
                + ("GROUNDED".equals(historyAvailability)
                ? "historyAvailability 为 GROUNDED：historyChunks 已提供与当前问题有关的历史证据，必须引用至少一个 chunk ID，且 historyStance 必须为 GROUNDED。"
                : "NO_EVIDENCE".equals(historyAvailability)
                ? "historyAvailability 为 NO_EVIDENCE：没有可用历史证据，historyStance 必须为 NO_EVIDENCE，usedChunkIds 必须为空。"
                : "")
                + (correctiveInstruction == null ? "" : correctiveInstruction)
                + "只输出严格 JSON。";
    }

    private String historyAvailability(CompanionContext context) {
        if (!context.chunks().isEmpty()) return CompanionReplyDraft.GROUNDED;
        return "NO_EVIDENCE".equals(context.retrievalDecision()) ? CompanionReplyDraft.NO_EVIDENCE : CompanionReplyDraft.NOT_APPLICABLE;
    }

    public JsonNode schema() {
        return object(
                "schemaVersion", enumValue(CompanionReplyDraft.SCHEMA_VERSION),
                "reply", string(),
                "historyStance", enumValue(CompanionReplyDraft.GROUNDED, CompanionReplyDraft.NO_EVIDENCE, CompanionReplyDraft.NOT_APPLICABLE),
                "usedMemoryIds", array(string()),
                "usedChunkIds", array(string()),
                "safetyMode", enumValue(CompanionReplyDraft.NORMAL, CompanionReplyDraft.SAFE_COMPLETION, CompanionReplyDraft.REFUSAL),
                "limitations", array(string()));
    }

    private ObjectNode object(Object... fields) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", false);
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = node.putArray("required");
        for (int index = 0; index < fields.length; index += 2) {
            properties.set((String) fields[index], (JsonNode) fields[index + 1]);
            required.add((String) fields[index]);
        }
        return node;
    }

    private ObjectNode string() {
        ObjectNode node = json.createObjectNode();
        node.put("type", "string");
        return node;
    }

    private ObjectNode enumValue(String... values) {
        ObjectNode node = string();
        ArrayNode array = node.putArray("enum");
        for (String value : values) array.add(value);
        return node;
    }

    private ObjectNode array(JsonNode item) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "array");
        node.set("items", item);
        return node;
    }

    public record Prompt(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
    }
}
