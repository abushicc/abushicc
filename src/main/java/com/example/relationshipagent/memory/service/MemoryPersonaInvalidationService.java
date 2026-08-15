package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.memory.repository.MemoryObservationRepository;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Invalidates derived Memory/Persona before source messages or sessions are rebuilt; audit rows remain intact.
 */
@Service
public class MemoryPersonaInvalidationService {
    private final MemoryObservationRepository observations;
    private final MemoryItemRepository memories;
    private final PersonaProfileRepository personas;
    private final ProcessingJobService jobs;

    public MemoryPersonaInvalidationService(MemoryObservationRepository observations, MemoryItemRepository memories, PersonaProfileRepository personas, ProcessingJobService jobs) {
        this.observations = observations;
        this.memories = memories;
        this.personas = personas;
        this.jobs = jobs;
    }

    @Transactional
    public void supersedeForSourceRebuild(String chatFileId, String upstream) {
        String reason = "superseded by upstream rebuild: " + upstream;
        for (String type : List.of(ProcessingJob.TYPE_MEMORY_EXTRACT, ProcessingJob.TYPE_MEMORY_AGGREGATE, ProcessingJob.TYPE_MEMORY_EMBED, ProcessingJob.TYPE_PERSONA_BUILD))
            jobs.cancelRunning(chatFileId, type, reason);
        Instant now = Instant.now();
        observations.update(null, new UpdateWrapper<MemoryObservation>().eq("chat_file_id", chatFileId).eq("status", MemoryObservation.STATUS_ACTIVE).set("status", MemoryObservation.STATUS_SUPERSEDED).set("updated_at", now));
        memories.update(null, new UpdateWrapper<MemoryItem>().eq("chat_file_id", chatFileId).eq("status", MemoryItem.STATUS_ACTIVE).set("status", MemoryItem.STATUS_SUPERSEDED).set("updated_at", now));
        personas.update(null, new UpdateWrapper<PersonaProfile>().eq("chat_file_id", chatFileId).in("status", PersonaProfile.STATUS_DRAFT, PersonaProfile.STATUS_ACTIVE).set("status", PersonaProfile.STATUS_SUPERSEDED).set("updated_at", now));
    }
}
