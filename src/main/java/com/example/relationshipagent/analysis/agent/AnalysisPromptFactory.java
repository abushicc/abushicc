package com.example.relationshipagent.analysis.agent;

import com.example.relationshipagent.analysis.evidence.EvidencePacket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates versioned prompts and a strict schema without exposing model output as trusted data.
 */
@Component
public class AnalysisPromptFactory {
    public static final String SCHEMA_VERSION = "analysis-draft-v1";
    public static final List<String> SECTION_KEYS = List.of("OVERVIEW", "RELATIONSHIP_STAGES", "COMMUNICATION_PATTERNS",
            "CONFLICT_TOPICS", "EMOTIONAL_TRENDS", "NEEDS_DIFFERENCES", "TURNING_POINTS", "RELATIONSHIP_ENDING", "POSSIBLE_FACTORS");
    private final ObjectMapper objectMapper;

    public AnalysisPromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnalysisPrompt create(List<EvidencePacket> packets, String question, Map<String, Object> userContext) {
        try {
            String user = objectMapper.writeValueAsString(Map.of(
                    "schemaVersion", SCHEMA_VERSION, "question", question == null ? "" : question,
                    "userContext", userContext == null ? Map.of() : userContext,
                    "evidencePackets", packets));
            return new AnalysisPrompt(systemPrompt(), user, schema());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Analysis evidence packets", e);
        }
    }

    private String systemPrompt() {
        return "你是关系对话分析助手。只基于 evidencePackets 输出 JSON。只能引用输入中已有的 evidenceRefId；"
                + "不得编造消息、时间、引用或唯一因果。用户上下文必须表述为用户标注。"
                + "FACT 至少一个 SUPPORT；INFERENCE 至少两个独立 SUPPORT 或一条统计加一条消息；"
                + "RELATIONSHIP_ENDING 与 POSSIBLE_FACTORS 的 INFERENCE 必须给反证或不确定性。"
                + "sections 必须严格按给定顺序包含九个 sectionKey 各一次；每个章节先用 summary 说明证据覆盖或不足。"
                + "对阶段/沟通节奏，若输入有阶段或统计证据，优先写可验证 FACT；对冲突、需求、情绪、结束，证据不足时 claims 为空，"
                + "不要为了填满章节臆测。不得把联系频率等同于感情、动机、人格、诊断或关系结束。";
    }

    public JsonNode schema() {
        ObjectNode claim = object("claimKey", string(), "claimType", enumValue("FACT", "INFERENCE", "HYPOTHESIS"),
                "statement", string(), "confidence", number(), "supportEvidenceRefIds", array(string()),
                "counterEvidenceRefIds", array(string()), "uncertaintyNote", string(), "alternativeExplanations", array(string()));
        ObjectNode section = object("sectionKey", enumValue(SECTION_KEYS.toArray(String[]::new)), "summary", string(), "claims", array(claim));
        ObjectNode coverage = object("summary", string(), "uncoveredPacketIds", array(string()));
        ObjectNode sections = array(section);
        sections.put("minItems", SECTION_KEYS.size());
        sections.put("maxItems", SECTION_KEYS.size());
        return object("schemaVersion", enumValue(SCHEMA_VERSION), "coverage", coverage, "sections", sections, "limitations", array(string()));
    }

    private ObjectNode object(Object... fields) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", false);
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = node.putArray("required");
        for (int i = 0; i < fields.length; i += 2) {
            properties.set((String) fields[i], (JsonNode) fields[i + 1]);
            required.add((String) fields[i]);
        }
        return node;
    }

    private ObjectNode string() {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "string");
        return n;
    }

    private ObjectNode number() {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "number");
        return n;
    }

    private ObjectNode enumValue(String... values) {
        ObjectNode n = string();
        ArrayNode e = n.putArray("enum");
        for (String value : values) e.add(value);
        return n;
    }

    private ObjectNode array(JsonNode item) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "array");
        n.set("items", item);
        return n;
    }

    public record AnalysisPrompt(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
    }
}
