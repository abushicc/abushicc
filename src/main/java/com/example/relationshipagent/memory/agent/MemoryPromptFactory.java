package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.memory.evidence.ObservationBatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Strict observation prompt/schema; all identifiers in output must originate from the server packet.
 */
@Component
public class MemoryPromptFactory {
    public static final String SCHEMA_VERSION = "memory-observation-draft-v1";
    public static final List<String> TYPES = List.of("FACT", "EVENT", "PREFERENCE", "DISLIKE", "EMOTIONAL_PATTERN", "COMMUNICATION_PATTERN", "RELATIONSHIP_PATTERN", "VALUE");
    public static final List<String> POLARITIES = List.of("POSITIVE", "NEGATIVE", "MIXED", "NEUTRAL", "UNKNOWN");
    private final ObjectMapper json;

    public MemoryPromptFactory(ObjectMapper json) {
        this.json = json;
    }

    public MemoryPrompt create(ObservationBatch batch, String targetPerson) {
        try {
            return new MemoryPrompt(developerPrompt(), json.writeValueAsString(Map.of("schemaVersion", SCHEMA_VERSION, "targetPerson", targetPerson, "sessions", batch.packets())), schema());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize observation evidence", e);
        }
    }

    private String developerPrompt() {
        return "你是单会话观察提取助手。只基于输入 sessions 为 targetPerson 输出 JSON。只能引用已有 evidenceRefId 和 sessionRefId。"
                + "Observation 只描述本会话直接支持的局部事实、事件、偏好或表达方式，不得把一次表达升级为长期人格、稳定价值观或关系结论。"
                + "不得编造消息、时间、引语、动机、诊断、第三方指控或 PII。没有可靠观察时返回空 observations。"
                + "observationKey 必须是可跨会话复用的、稳定的英文 snake_case 语义键（如 prefers_outdoor_walks），不得包含 sessionRefId、消息 ID、日期、序号或 SES/session 前缀。"
                + "当同一输入批次中不同会话表达同一语义时，必须复用完全相同的 observationKey、observationType 和 polarity；例如多次关心对方疲累并劝其休息，应统一为同一个 COMMUNICATION_PATTERN，而不能一处写 EVENT、另一处写 COMMUNICATION_PATTERN。"
                + "对于目标人物明确关心对方疲累、睡眠或身体状态并建议其休息的交流，固定使用 encourages_other_to_rest / COMMUNICATION_PATTERN / POSITIVE；只有存在直接证据时才使用此规范键。"
                + "support 至少一条；counter 只能使用同会话已有引用；不得使用绝对措辞。";
    }

    public JsonNode schema() {
        ObjectNode item = object("observationKey", string(), "observationType", enumValue(TYPES.toArray(String[]::new)), "statement", string(), "polarity", enumValue(POLARITIES.toArray(String[]::new)), "confidence", number(), "supportEvidenceRefIds", array(string()), "counterEvidenceRefIds", array(string()), "uncertaintyNote", string());
        ObjectNode session = object("sessionRefId", string(), "observations", array(item));
        return object("schemaVersion", enumValue(SCHEMA_VERSION), "sessions", array(session), "limitations", array(string()));
    }

    private ObjectNode object(Object... fields) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "object");
        n.put("additionalProperties", false);
        ObjectNode p = n.putObject("properties");
        ArrayNode r = n.putArray("required");
        for (int i = 0; i < fields.length; i += 2) {
            p.set((String) fields[i], (JsonNode) fields[i + 1]);
            r.add((String) fields[i]);
        }
        return n;
    }

    private ObjectNode string() {
        ObjectNode n = json.createObjectNode();
        n.put("type", "string");
        return n;
    }

    private ObjectNode number() {
        ObjectNode n = json.createObjectNode();
        n.put("type", "number");
        n.put("minimum", 0);
        n.put("maximum", 1);
        return n;
    }

    private ObjectNode enumValue(String... values) {
        ObjectNode n = string();
        ArrayNode e = n.putArray("enum");
        for (String v : values) e.add(v);
        return n;
    }

    private ObjectNode array(JsonNode item) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "array");
        n.set("items", item);
        return n;
    }

    public record MemoryPrompt(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
    }
}
