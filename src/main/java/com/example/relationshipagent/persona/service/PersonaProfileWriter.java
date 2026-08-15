package com.example.relationshipagent.persona.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.persona.agent.PersonaPromptFactory;
import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.model.PersonaProfileMemory;
import com.example.relationshipagent.persona.repository.PersonaProfileMemoryRepository;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.persona.validation.PersonaDraftValidator;
import com.example.relationshipagent.persona.validation.PersonaFewShotReferenceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Writes an immutable DRAFT Persona; activation/edits are deliberately separate operations.
 */
@Service
public class PersonaProfileWriter {
    private final PersonaProfileRepository profiles;
    private final PersonaProfileMemoryRepository links;
    private final ObjectMapper json;
    private final PersonaFewShotReferenceResolver fewShotResolver;

    public PersonaProfileWriter(PersonaProfileRepository profiles, PersonaProfileMemoryRepository links, ObjectMapper json, PersonaFewShotReferenceResolver fewShotResolver) {
        this.profiles = profiles;
        this.links = links;
        this.json = json;
        this.fewShotResolver = fewShotResolver;
    }

    @Transactional
    public PersonaProfile write(PersonaBuildInput input, String inputHash, String personaVersion, String promptVersion, String model, String provider, String agentRunId, PersonaDraftValidator.ValidationResult validation) {
        return writeInternal(input, inputHash, personaVersion, promptVersion, model, provider, agentRunId, validation, "MODEL_BUILD", null);
    }

    @Transactional
    public PersonaProfile writeHumanEdit(PersonaBuildInput input, PersonaProfile base, String inputHash, PersonaDraftValidator.ValidationResult validation) {
        return writeInternal(input, inputHash, base.getPersonaVersion(), base.getPromptVersion(), "HUMAN", null, null, validation, "HUMAN_EDIT", base.getId());
    }

    private PersonaProfile writeInternal(PersonaBuildInput input, String inputHash, String personaVersion, String promptVersion, String model, String provider, String agentRunId, PersonaDraftValidator.ValidationResult validation, String changeType, String parentId) {
        // Persona 草稿只持有 memory/message ID；所有引用先由服务端复核，再以不可变版本写入。
        if (!validation.valid()) throw new IllegalArgumentException("Persona Draft failed server validation");
        if (!fewShotResolver.valid(input.chatFileId(), input.targetPerson(), validation.fewShotExamples()))
            throw new IllegalStateException("Persona few-shot references are no longer valid");
        Instant now = Instant.now();
        PersonaProfile previous = parentId == null ? profiles.selectOne(new LambdaQueryWrapper<PersonaProfile>().eq(PersonaProfile::getChatFileId, input.chatFileId()).eq(PersonaProfile::getTargetPerson, input.targetPerson()).eq(PersonaProfile::getStatus, PersonaProfile.STATUS_ACTIVE).orderByDesc(PersonaProfile::getCreatedAt).last("limit 1")) : null;
        Set<String> memoryIds = new LinkedHashSet<>();
        validation.features().forEach(f -> memoryIds.addAll(f.sourceMemoryIds()));
        PersonaProfile row = new PersonaProfile();
        row.setId(UUID.randomUUID().toString());
        row.setChatFileId(input.chatFileId());
        row.setTargetPerson(input.targetPerson());
        row.setVersion("draft-" + now.toEpochMilli() + "-" + row.getId().substring(0, 8));
        row.setProfileJson(profileJson(input, validation));
        row.setBasedOnMemoryIds(asJson(memoryIds));
        row.setStatus(PersonaProfile.STATUS_DRAFT);
        row.setCreatedAt(now);
        row.setInputHash(inputHash);
        row.setPersonaVersion(personaVersion);
        row.setPromptVersion(promptVersion);
        row.setModelName(model);
        row.setProviderName(provider);
        row.setParentProfileId(parentId == null ? (previous == null ? null : previous.getId()) : parentId);
        row.setChangeType(changeType);
        row.setValidationJson(asJson(Map.of("errors", validation.errors(), "featureCount", validation.features().size(), "fewShotCount", validation.fewShotExamples().size())));
        row.setCoverageNote("Few-shot is stored as original message IDs and must be rehydrated by the server at response time.");
        row.setAgentRunId(agentRunId);
        row.setUpdatedAt(now);
        profiles.insert(row);
        int ordinal = 0;
        for (var feature : validation.features())
            for (String memoryId : feature.sourceMemoryIds()) {
                PersonaProfileMemory link = new PersonaProfileMemory();
                link.setPersonaProfileId(row.getId());
                link.setMemoryItemId(memoryId);
                link.setFeaturePath(feature.path());
                link.setOrdinal(ordinal++);
                links.insert(link);
            }
        return row;
    }

    private String profileJson(PersonaBuildInput input, PersonaDraftValidator.ValidationResult validation) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("schemaVersion", PersonaPromptFactory.SCHEMA_VERSION);
        profile.put("targetPerson", input.targetPerson());
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("from", input.rangeFrom());
        timeRange.put("to", input.rangeTo());
        profile.put("timeRange", timeRange);
        Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
        for (var feature : validation.features())
            sections.computeIfAbsent(feature.path().replaceAll("\\[.*", ""), ignored -> new ArrayList<>()).add(Map.of("statement", feature.statement(), "sourceMemoryIds", feature.sourceMemoryIds()));
        profile.put("features", sections);
        profile.put("fewShotExamples", validation.fewShotExamples());
        profile.put("safetyBoundaries", List.of("这不是目标人物本人。", "不伪造未在历史中出现的事件。", "没有证据时明确表示不记得。"));
        profile.put("coverage", Map.of("memoryCount", input.memories().size(), "sessionCandidateCount", input.fewShotCandidates().size(), "limitations", List.of("媒体语义尚未分析。", "长尾时期的数据可能稀疏。")));
        return asJson(profile);
    }

    private String asJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize Persona profile", e);
        }
    }
}
