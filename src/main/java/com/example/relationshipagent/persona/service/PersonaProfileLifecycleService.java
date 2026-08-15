package com.example.relationshipagent.persona.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.model.PersonaProfileMemory;
import com.example.relationshipagent.persona.repository.PersonaProfileMemoryRepository;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable activation and rollback rules. No historical Persona row is ever reactivated in place.
 */
@Service
public class PersonaProfileLifecycleService {
    private final PersonaProfileRepository profiles;
    private final PersonaProfileMemoryRepository links;

    public PersonaProfileLifecycleService(PersonaProfileRepository profiles, PersonaProfileMemoryRepository links) {
        this.profiles = profiles;
        this.links = links;
    }

    @Transactional
    public PersonaProfile activate(String profileId) {
        PersonaProfile draft = profiles.selectById(profileId);
        if (draft == null) throw new IllegalArgumentException("Persona profile not found");
        if (!PersonaProfile.STATUS_DRAFT.equals(draft.getStatus()))
            throw new IllegalStateException("Only DRAFT Persona can be activated");
        Instant now = Instant.now();
        profiles.update(null, new UpdateWrapper<PersonaProfile>().eq("chat_file_id", draft.getChatFileId()).eq("target_person", draft.getTargetPerson()).eq("status", PersonaProfile.STATUS_ACTIVE).set("status", PersonaProfile.STATUS_SUPERSEDED).set("updated_at", now));
        int changed = profiles.update(null, new UpdateWrapper<PersonaProfile>().eq("id", profileId).eq("status", PersonaProfile.STATUS_DRAFT).set("status", PersonaProfile.STATUS_ACTIVE).set("activated_at", now).set("updated_at", now));
        if (changed != 1) throw new IllegalStateException("Persona activation lost its draft state");
        return profiles.selectById(profileId);
    }

    @Transactional
    public PersonaProfile rollbackAsDraft(String historicalProfileId) {
        PersonaProfile source = profiles.selectById(historicalProfileId);
        if (source == null) throw new IllegalArgumentException("Persona profile not found");
        Instant now = Instant.now();
        PersonaProfile active = profiles.selectOne(new QueryWrapper<PersonaProfile>().eq("chat_file_id", source.getChatFileId()).eq("target_person", source.getTargetPerson()).eq("status", PersonaProfile.STATUS_ACTIVE).orderByDesc("created_at").last("limit 1"));
        PersonaProfile copy = new PersonaProfile();
        copy.setId(UUID.randomUUID().toString());
        copy.setChatFileId(source.getChatFileId());
        copy.setTargetPerson(source.getTargetPerson());
        copy.setVersion("rollback-" + now.toEpochMilli() + "-" + copy.getId().substring(0, 8));
        copy.setProfileJson(source.getProfileJson());
        copy.setBasedOnMemoryIds(source.getBasedOnMemoryIds());
        copy.setStatus(PersonaProfile.STATUS_DRAFT);
        copy.setCreatedAt(now);
        copy.setInputHash(source.getInputHash());
        copy.setPersonaVersion(source.getPersonaVersion());
        copy.setPromptVersion(source.getPromptVersion());
        copy.setModelName(source.getModelName());
        copy.setProviderName(source.getProviderName());
        copy.setParentProfileId(active == null ? null : active.getId());
        copy.setChangeType("ROLLBACK");
        copy.setValidationJson(source.getValidationJson());
        copy.setCoverageNote(source.getCoverageNote());
        copy.setUpdatedAt(now);
        profiles.insert(copy);
        List<PersonaProfileMemory> sourceLinks = links.selectList(new QueryWrapper<PersonaProfileMemory>().eq("persona_profile_id", source.getId()).orderByAsc("ordinal"));
        for (var sourceLink : sourceLinks) {
            PersonaProfileMemory link = new PersonaProfileMemory();
            link.setPersonaProfileId(copy.getId());
            link.setMemoryItemId(sourceLink.getMemoryItemId());
            link.setFeaturePath(sourceLink.getFeaturePath());
            link.setOrdinal(sourceLink.getOrdinal());
            links.insert(link);
        }
        return copy;
    }
}
