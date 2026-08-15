package com.example.relationshipagent.memory.validation;

import com.example.relationshipagent.memory.agent.*;
import com.example.relationshipagent.memory.evidence.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ObservationDraftValidatorTest {
    private final ObservationDraftValidator validator=new ObservationDraftValidator();
    private final ObservationEvidencePacket packet=new ObservationEvidencePacket("SES-000001","s",Instant.EPOCH,Instant.EPOCH,"她",2,false,List.of(new ObservationEvidenceRef("MES-000001","m1","s",Instant.EPOCH,"她","我喜欢","TEXT"),new ObservationEvidenceRef("MES-000002","m2","s",Instant.EPOCH,"她","这个不错","TEXT")),List.of());
    @Test void shouldAcceptDirectFactAndCapConfidence(){
        var item=new ObservationDraft.ObservationItemDraft("pref","PREFERENCE","她在该会话表达喜欢", "POSITIVE",.99,List.of("MES-000001"),List.of(),"");
        var result=validator.validate(draft(item),List.of(packet));
        assertThat(result.observations()).singleElement().satisfies(v->{assertThat(v.status()).isEqualTo("VALID");assertThat(v.confidence()).isEqualTo(.70);});
    }
    @Test void shouldRejectUnknownEvidenceAndReviewHighRiskWording(){
        var bad=new ObservationDraft.ObservationItemDraft("x","FACT","人格障碍", "UNKNOWN",.5,List.of("NOPE"),List.of(),"");
        assertThat(validator.validate(draft(bad),List.of(packet)).observations()).singleElement().satisfies(v->assertThat(v.status()).isEqualTo("REJECTED"));
    }
    @Test void shouldRejectSessionScopedKeyBecauseItCannotAggregateAcrossSessions(){
        var item=new ObservationDraft.ObservationItemDraft("SES000042_prefers_walks","PREFERENCE","她在该会话提到散步", "POSITIVE",.6,List.of("MES-000001"),List.of(),"");
        assertThat(validator.validate(draft(item),List.of(packet)).observations()).singleElement().satisfies(v->{assertThat(v.status()).isEqualTo("REJECTED");assertThat(v.errors()).contains("SESSION_SCOPED_OBSERVATION_KEY");});
    }
    @Test void shouldNormalizeKnownRestEncouragementSynonym(){
        var item=new ObservationDraft.ObservationItemDraft("encourages_rest_when_other_is_tired","EVENT","她劝对方休息", "NEUTRAL",.6,List.of("MES-000001","MES-000002"),List.of(),"");
        assertThat(validator.validate(draft(item),List.of(packet)).observations()).singleElement().satisfies(v->{
            assertThat(v.draft().observationKey()).isEqualTo("encourages_other_to_rest");
            assertThat(v.draft().observationType()).isEqualTo("COMMUNICATION_PATTERN");
            assertThat(v.draft().polarity()).isEqualTo("POSITIVE");
        });
    }
    @Test void shouldNormalizeKnownSleepConcernSynonym(){
        var item=new ObservationDraft.ObservationItemDraft("shows_concern_about_others_poor_sleep","EVENT","她关心对方睡眠", "NEUTRAL",.6,List.of("MES-000001","MES-000002"),List.of(),"");
        assertThat(validator.validate(draft(item),List.of(packet)).observations()).singleElement().satisfies(v->{
            assertThat(v.draft().observationKey()).isEqualTo("checks_on_others_sleep");
            assertThat(v.draft().observationType()).isEqualTo("COMMUNICATION_PATTERN");
            assertThat(v.draft().polarity()).isEqualTo("POSITIVE");
        });
    }
    @Test void shouldNormalizeKnownHealthConcernSynonym(){
        var item=new ObservationDraft.ObservationItemDraft("urges_other_to_seek_medical_care","EVENT","她建议对方照顾健康", "NEUTRAL",.6,List.of("MES-000001","MES-000002"),List.of(),"");
        assertThat(validator.validate(draft(item),List.of(packet)).observations()).singleElement().satisfies(v->{
            assertThat(v.draft().observationKey()).isEqualTo("encourages_other_to_care_for_health");
            assertThat(v.draft().observationType()).isEqualTo("COMMUNICATION_PATTERN");
            assertThat(v.draft().polarity()).isEqualTo("POSITIVE");
        });
    }
    private ObservationDraft draft(ObservationDraft.ObservationItemDraft item){return new ObservationDraft(MemoryPromptFactory.SCHEMA_VERSION,List.of(new ObservationDraft.SessionObservationDraft("SES-000001",List.of(item))),List.of());}
}
