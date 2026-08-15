package com.example.relationshipagent.persona.agent;

import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Persona prompt exposes only approved Memory, deterministic metrics and opaque few-shot references.
 */
@Component
public class PersonaPromptFactory {
    public static final String SCHEMA_VERSION = "persona-profile-v1";
    private final ObjectMapper json;

    public PersonaPromptFactory(ObjectMapper json) {
        this.json = json;
    }

    public PersonaPrompt create(PersonaBuildInput input) {
        try {
            return new PersonaPrompt(system(), json.writeValueAsString(Map.of("schemaVersion", SCHEMA_VERSION, "targetPerson", input.targetPerson(), "memories", input.memories().stream().map(m -> Map.of("memoryId", m.getId(), "memoryType", m.getMemoryType(), "content", m.getContent(), "polarity", m.getPolarity())).toList(), "styleFingerprint", input.styleFingerprint(), "fewShotCandidates", input.fewShotCandidates())), schema());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Persona build input", e);
        }
    }

    private String system() {
        return "你基于 approved memories 和 deterministic styleFingerprint 生成可审阅 Persona JSON。每个非空 feature 只能引用输入 memoryId；不作诊断、读心、绝对人格判断或历史伪造。所有 feature statement 禁止出现这些高风险字面词：人格障碍、抑郁、焦虑症、出轨、违法、肯定、永远、从不、唯一；需要表达不确定性时使用‘在这些记录中’或‘曾经’等有证据边界的措辞。communicationStyle 若没有 approved Memory 支持，必须返回 statement='' 和 sourceMemoryIds=[]，并在 limitations 说明证据不足，不得以 styleFingerprint 单独断言人格。fewShotExamples 只能从 fewShotCandidates 原样选择 ID，绝不能输出示例文本、改写文本或新增 ID。若 fewShotCandidates 非空，必须选择 1 到 3 个最有代表性的候选；只有候选列表为空时才返回空 fewShotExamples。证据不足时返回空 feature 列表并写 limitations。";
    }

    public JsonNode schema() {
        ObjectNode feature = object("statement", string(), "sourceMemoryIds", array(string()));
        ObjectNode few = object("sessionId", string(), "contextMessageIds", array(string()), "targetMessageIds", array(string()));
        return object("schemaVersion", enumValue(SCHEMA_VERSION), "communicationStyle", feature, "preferences", array(feature), "dislikes", array(feature), "interactionPatterns", array(feature), "emotionalExpression", array(feature), "values", array(feature), "boundaries", array(feature), "fewShotExamples", array(few), "limitations", array(string()));
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

    private ObjectNode enumValue(String... values) {
        ObjectNode n = string();
        ArrayNode a = n.putArray("enum");
        for (String v : values) a.add(v);
        return n;
    }

    private ObjectNode array(JsonNode child) {
        ObjectNode n = json.createObjectNode();
        n.put("type", "array");
        n.set("items", child);
        return n;
    }

    public record PersonaPrompt(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
    }
}
