package com.example.relationshipagent.persona.agent;

import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PersonaPromptFactoryTest {
    @Test void shouldRequireReferenceOnlyFewShotSchema(){
        var factory=new PersonaPromptFactory(new ObjectMapper().findAndRegisterModules());
        var prompt=factory.create(new PersonaBuildInput("f","她",List.of(),new ObjectMapper().createObjectNode(),null,null,List.of()));
        var few=prompt.jsonSchema().path("properties").path("fewShotExamples").path("items").path("properties");
        assertThat(few.has("targetMessageIds")).isTrue();
        assertThat(few.has("text")).isFalse();
    }
}
