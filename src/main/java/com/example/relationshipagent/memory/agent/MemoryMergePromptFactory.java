package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.memory.model.MemoryObservation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned, narrow prompt for cross-session merging; it receives observations, never raw chat.
 */
@Component
public class MemoryMergePromptFactory {
    public static final String SCHEMA_VERSION = "memory-merge-v1";
    public static final List<String> TYPES = List.of("FACT", "EVENT", "PREFERENCE", "DISLIKE", "EMOTIONAL_PATTERN", "COMMUNICATION_PATTERN", "RELATIONSHIP_PATTERN", "VALUE");
    public static final List<String> POLARITIES = List.of("POSITIVE", "NEGATIVE", "MIXED", "NEUTRAL", "UNKNOWN");
    private final ObjectMapper json;

    public MemoryMergePromptFactory(ObjectMapper json) {
        this.json = json;
    }

    public MergePrompt create(List<MemoryObservation> observations, String targetPerson) {
        try {
            List<Map<String, Object>> rows = observations.stream().map(o -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("observationId", o.getId());
                row.put("observationKey", o.getObservationKey());
                row.put("observationType", o.getObservationType());
                row.put("statement", o.getStatement());
                row.put("polarity", o.getPolarity());
                row.put("confidence", o.getConfidence());
                row.put("validFrom", o.getValidFrom());
                row.put("validTo", o.getValidTo());
                return row;
            }).toList();
            return new MergePrompt(systemPrompt(), json.writeValueAsString(Map.of(
                    "schemaVersion", SCHEMA_VERSION, "targetPerson", targetPerson, "observations", rows)), schema());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize memory merge input", e);
        }
    }

    private String systemPrompt() {
        return "你是长期记忆聚合器。只基于 observations 输出 JSON；只能引用已有 observationId。输入可能含多个 observationKey/type/polarity 组，必须逐组独立处理，绝不能跨 key、type 或 polarity 合并来源。不得编造人物事实，不得把冲突平均成模糊结论。不同时间或极性冲突必须分开。FACT/EVENT 可单会话，偏好和模式至少需要两个独立会话，否则保留 conflictNote 并降低置信度。高风险诊断、违法、出轨等内容输出空 items。";
    }

    public JsonNode schema() {
        ObjectNode item = object("memoryKey", string(), "memoryType", enumValue(TYPES.toArray(String[]::new)), "content", string(),
                "polarity", enumValue(POLARITIES.toArray(String[]::new)), "confidence", number(), "sourceObservationIds", array(string()),
                "validFrom", string(), "validTo", string(), "conflictNote", string());
        return object("schemaVersion", enumValue(SCHEMA_VERSION), "items", array(item), "limitations", array(string()));
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
        return n;
    }

    private ObjectNode enumValue(String... values) {
        ObjectNode n = string();
        ArrayNode a = n.putArray("enum");
        for (String v : values) a.add(v);
        return n;
    }

    private ObjectNode array(JsonNode item) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "array");
        n.set("items", item);
        return n;
    }

    public record MergePrompt(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
    }
}
