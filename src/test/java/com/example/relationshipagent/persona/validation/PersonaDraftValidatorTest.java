package com.example.relationshipagent.persona.validation;

import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.persona.agent.PersonaDraft;
import com.example.relationshipagent.persona.agent.PersonaPromptFactory;
import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.example.relationshipagent.persona.input.PersonaFewShotCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PersonaDraftValidatorTest {
    @Test void shouldAcceptApprovedMemoryAndExactFewShotId(){
        var input=input();var feature=new PersonaDraft.FeatureDraft("她表达直接",List.of("m1"));var few=new PersonaDraft.FewShotDraft("s1",List.of("c1"),List.of("t1"));
        var draft=new PersonaDraft(PersonaPromptFactory.SCHEMA_VERSION,feature,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(few),List.of());
        var result=new PersonaDraftValidator().validate(draft,input);assertThat(result.valid()).isTrue();assertThat(result.features()).hasSize(1);
    }
    @Test void shouldRejectFabricatedFewShotOrUnknownMemory(){
        var feature=new PersonaDraft.FeatureDraft("她表达直接",List.of("unknown"));var few=new PersonaDraft.FewShotDraft("s1",List.of("c1"),List.of("nope"));
        var draft=new PersonaDraft(PersonaPromptFactory.SCHEMA_VERSION,feature,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(few),List.of());
        assertThat(new PersonaDraftValidator().validate(draft,input()).valid()).isFalse();
    }
    @Test void shouldRequireFewShotWhenServerHasCandidates(){
        var feature=new PersonaDraft.FeatureDraft("她表达直接",List.of("m1"));
        var draft=new PersonaDraft(PersonaPromptFactory.SCHEMA_VERSION,feature,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        assertThat(new PersonaDraftValidator().validate(draft,input()).errors()).contains("MISSING_FEW_SHOT_EXAMPLE");
    }
    @Test void shouldAllowEmptyCommunicationStyleRatherThanInventingAnUnsupportedFeature(){
        var few=new PersonaDraft.FewShotDraft("s1",List.of("c1"),List.of("t1"));
        var empty=new PersonaDraft.FeatureDraft("",List.of());
        var draft=new PersonaDraft(PersonaPromptFactory.SCHEMA_VERSION,empty,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(few),List.of("证据不足"));
        assertThat(new PersonaDraftValidator().validate(draft,input()).valid()).isTrue();
    }
    private PersonaBuildInput input(){MemoryItem m=new MemoryItem();m.setId("m1");m.setStatus(MemoryItem.STATUS_ACTIVE);m.setReviewStatus(MemoryItem.REVIEW_APPROVED);return new PersonaBuildInput("f","她",List.of(m),new ObjectMapper().createObjectNode(),null,null,List.of(new PersonaFewShotCandidate("s1",List.of("c1"),List.of("t1"))));}
}
