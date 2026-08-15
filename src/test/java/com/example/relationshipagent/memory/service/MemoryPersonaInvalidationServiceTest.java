package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.memory.repository.MemoryObservationRepository;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class MemoryPersonaInvalidationServiceTest {
    @Test void sourceRebuildCancelsAllMemoryAndPersonaWorkers(){
        var observations=mock(MemoryObservationRepository.class);var memories=mock(MemoryItemRepository.class);var personas=mock(PersonaProfileRepository.class);var jobs=mock(ProcessingJobService.class);
        new MemoryPersonaInvalidationService(observations,memories,personas,jobs).supersedeForSourceRebuild("f","SESSIONIZE");
        for(String type:new String[]{ProcessingJob.TYPE_MEMORY_EXTRACT,ProcessingJob.TYPE_MEMORY_AGGREGATE,ProcessingJob.TYPE_MEMORY_EMBED,ProcessingJob.TYPE_PERSONA_BUILD})verify(jobs).cancelRunning(eq("f"),eq(type),contains("SESSIONIZE"));
        verify(observations).update(any(),any());verify(memories).update(any(),any());verify(personas).update(any(),any());
    }
}
