package com.example.relationshipagent.persona.service;

import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.persona.agent.PersonaDraft;
import com.example.relationshipagent.persona.input.PersonaBuildInputService;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.persona.validation.PersonaDraftValidator;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a validated HUMAN_EDIT DRAFT; direct JSON replacement is deliberately unavailable.
 */
@Service
public class PersonaProfileEditService {
    private final PersonaProfileRepository profiles;
    private final PersonaBuildInputService inputs;
    private final PersonaDraftValidator validator;
    private final PersonaProfileWriter writer;

    public PersonaProfileEditService(PersonaProfileRepository profiles, PersonaBuildInputService inputs, PersonaDraftValidator validator, PersonaProfileWriter writer) {
        this.profiles = profiles;
        this.inputs = inputs;
        this.validator = validator;
        this.writer = writer;
    }

    @Transactional
    public PersonaProfile createDraft(String profileId, PersonaDraft draft) {
        PersonaProfile base = profiles.selectById(profileId);
        if (base == null) throw new BizException(ErrorCode.PERSONA_PROFILE_NOT_FOUND);
        var input = inputs.build(base.getChatFileId(), base.getTargetPerson());
        var checked = validator.validate(draft, input);
        if (!checked.valid())
            throw new BizException(ErrorCode.PARAM_INVALID, "Persona edit failed validation: " + String.join(",", checked.errors()));
        String hash = ProcessingJobService.hashInput(base.getInputHash() == null ? "" : base.getInputHash(), "HUMAN_EDIT", draft.toString());
        return writer.writeHumanEdit(input, base, hash, checked);
    }
}
