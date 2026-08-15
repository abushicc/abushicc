package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.memory.repository.MemoryObservationRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryAggregationCandidateServiceTest {
    @Test void shouldOnlyReturnGroupsThatMeetDeterministicEvidenceThreshold(){
        MemoryObservationRepository observations=mock(MemoryObservationRepository.class);
        when(observations.selectList(any())).thenReturn(List.of(
                row("event","EVENT","NEUTRAL","s1"),
                row("single-preference","PREFERENCE","POSITIVE","s1"),
                row("stable-preference","PREFERENCE","POSITIVE","s1"),
                row("stable-preference","PREFERENCE","POSITIVE","s2")
        ));
        var result=new MemoryAggregationCandidateService(observations).find("f","kiwi");
        assertThat(result).extracting(MemoryAggregationCandidateService.Candidate::memoryKey).containsExactly("event","stable-preference");
        assertThat(result.get(1).independentSessionCount()).isEqualTo(2);
    }
    private static MemoryObservation row(String key,String type,String polarity,String session){MemoryObservation value=new MemoryObservation();value.setId(key+"-"+session);value.setChatFileId("f");value.setTargetPerson("kiwi");value.setObservationKey(key);value.setObservationType(type);value.setPolarity(polarity);value.setSourceSessionId(session);value.setStatus(MemoryObservation.STATUS_ACTIVE);value.setValidationStatus(MemoryObservation.VALID);value.setCreatedAt(Instant.EPOCH);return value;}
}
