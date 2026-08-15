package com.example.relationshipagent.companion.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionContextBuilderTest {
    @Test void expandsInternshipPressureQueriesToAStableLexicalAnchor() {
        assertThat(CompanionContextBuilder.eventIntentQuery("她对实习和工作压力大的时候是怎么吐槽的"))
                .isEqualTo("实习");
    }

    @Test void keepsAnExplicitInternshipIntentAheadOfBroadTopicFragments() {
        assertThat(CompanionContextBuilder.eventIntentQuery("她对实习和工作压力大的时候是怎么吐槽的"))
                .isEqualTo("实习");
    }
}
