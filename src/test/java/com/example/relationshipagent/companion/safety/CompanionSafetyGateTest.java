package com.example.relationshipagent.companion.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionSafetyGateTest {
    private final CompanionSafetyGate gate = new CompanionSafetyGate();

    @Test void interceptsImpersonationAndKeepsNormalNegativeMessageOutOfSafetyPath() {
        assertThat(gate.evaluate("替我给她发一句晚安").handled()).isTrue();
        assertThat(gate.evaluate("你是真人吗").ruleCode()).isEqualTo("IDENTITY_BOUNDARY");
        assertThat(gate.evaluate("我现在很不高兴").handled()).isFalse();
    }
}
