package com.example.relationshipagent.persona.input;

import java.util.List;

/**
 * Server-selected opaque references only; no message text crosses the Persona model boundary.
 */
public record PersonaFewShotCandidate(String sessionId, List<String> contextMessageIds, List<String> targetMessageIds) {
}
