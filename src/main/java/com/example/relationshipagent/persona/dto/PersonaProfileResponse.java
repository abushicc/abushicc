package com.example.relationshipagent.persona.dto;

import com.example.relationshipagent.persona.model.PersonaProfile;

import java.time.Instant;

/**
 * Persona review DTO excludes internal validation/audit records while keeping the rendered DRAFT reviewable.
 */
public record PersonaProfileResponse(String id, String targetPerson, String version, String status, String profileJson,
                                     String parentProfileId, String changeType, Instant createdAt, Instant activatedAt,
                                     String coverageNote) {
    public static PersonaProfileResponse from(PersonaProfile p) {
        return new PersonaProfileResponse(p.getId(), p.getTargetPerson(), p.getVersion(), p.getStatus(), p.getProfileJson(), p.getParentProfileId(), p.getChangeType(), p.getCreatedAt(), p.getActivatedAt(), p.getCoverageNote());
    }
}
