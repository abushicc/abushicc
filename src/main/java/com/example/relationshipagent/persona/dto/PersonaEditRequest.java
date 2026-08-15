package com.example.relationshipagent.persona.dto;

import com.example.relationshipagent.persona.agent.PersonaDraft;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Human edit uses the same evidence-bearing draft contract as model output.
 */
public record PersonaEditRequest(@NotNull @Valid PersonaDraft draft) {
}
