package com.example.relationshipagent.persona.service;

import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileMemoryRepository;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PersonaProfileLifecycleServiceTest {
    @Test void activationSupersedesOldAndPromotesOnlyDraft(){
        PersonaProfile draft=new PersonaProfile();draft.setId("d");draft.setChatFileId("f");draft.setTargetPerson("她");draft.setStatus(PersonaProfile.STATUS_DRAFT);
        PersonaProfileRepository profiles=mock(PersonaProfileRepository.class);when(profiles.selectById("d")).thenReturn(draft);when(profiles.update(any(),ArgumentMatchers.any())).thenReturn(1);
        PersonaProfile result=new PersonaProfileLifecycleService(profiles,mock(PersonaProfileMemoryRepository.class)).activate("d");
        verify(profiles,times(2)).update(any(),any());assertThat(result).isSameAs(draft);
    }
    @Test void rollbackCreatesNewDraftInsteadOfReactivatingHistory(){
        PersonaProfile source=new PersonaProfile();source.setId("old");source.setChatFileId("f");source.setTargetPerson("她");source.setStatus(PersonaProfile.STATUS_SUPERSEDED);source.setProfileJson("{}");
        PersonaProfileRepository profiles=mock(PersonaProfileRepository.class);when(profiles.selectById("old")).thenReturn(source);when(profiles.selectOne(any())).thenReturn(null);
        PersonaProfile result=new PersonaProfileLifecycleService(profiles,mock(PersonaProfileMemoryRepository.class)).rollbackAsDraft("old");
        verify(profiles).insert(any(PersonaProfile.class));assertThat(result.getStatus()).isEqualTo(PersonaProfile.STATUS_DRAFT);assertThat(result.getChangeType()).isEqualTo("ROLLBACK");assertThat(result.getId()).isNotEqualTo("old");
    }
}
