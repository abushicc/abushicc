package com.example.relationshipagent.persona.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.persona.dto.PersonaProfileResponse;
import com.example.relationshipagent.persona.dto.PersonaEditRequest;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import com.example.relationshipagent.persona.service.PersonaBuildOrchestrator;
import com.example.relationshipagent.persona.service.PersonaProfileLifecycleService;
import com.example.relationshipagent.persona.service.PersonaProfileEditService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

/**
 * Persona build/review routes; human edits use the same evidence-bearing Draft validator.
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/personas")
public class PersonaProfileController {
    private final PersonaBuildOrchestrator builds;
    private final PersonaProfileLifecycleService lifecycle;
    private final PersonaProfileEditService edits;
    private final PersonaProfileRepository profiles;
    private final ChatFileRepository files;

    public PersonaProfileController(PersonaBuildOrchestrator builds, PersonaProfileLifecycleService lifecycle, PersonaProfileEditService edits, PersonaProfileRepository profiles, ChatFileRepository files) {
        this.builds = builds;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.profiles = profiles;
        this.files = files;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PersonaBuildOrchestrator.Accepted>> build(@PathVariable String chatFileId, @RequestParam(required = false) String targetPerson) {
        var accepted = builds.request(chatFileId, targetPerson);
        return ResponseEntity.status(accepted.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @GetMapping
    public ApiResponse<List<PersonaProfileResponse>> list(@PathVariable String chatFileId, @RequestParam(required = false) String targetPerson, @RequestParam(defaultValue = "50") int size) {
        file(chatFileId);
        int safe = Math.min(Math.max(size, 1), 100);
        QueryWrapper<PersonaProfile> q = new QueryWrapper<PersonaProfile>().eq("chat_file_id", chatFileId).orderByDesc("created_at").last("LIMIT " + safe);
        if (targetPerson != null && !targetPerson.isBlank()) q.eq("target_person", targetPerson);
        return ApiResponse.ok(profiles.selectList(q).stream().map(PersonaProfileResponse::from).toList());
    }

    @GetMapping("/{profileId}")
    public ApiResponse<PersonaProfileResponse> detail(@PathVariable String chatFileId, @PathVariable String profileId) {
        return ApiResponse.ok(PersonaProfileResponse.from(owned(chatFileId, profileId)));
    }

    @PostMapping("/{profileId}/activate")
    public ApiResponse<PersonaProfileResponse> activate(@PathVariable String chatFileId, @PathVariable String profileId) {
        owned(chatFileId, profileId);
        return ApiResponse.ok(PersonaProfileResponse.from(lifecycle.activate(profileId)));
    }

    @PostMapping("/{profileId}/rollback")
    public ApiResponse<PersonaProfileResponse> rollback(@PathVariable String chatFileId, @PathVariable String profileId) {
        owned(chatFileId, profileId);
        return ApiResponse.ok(PersonaProfileResponse.from(lifecycle.rollbackAsDraft(profileId)));
    }

    @PostMapping("/{profileId}/edits")
    public ApiResponse<PersonaProfileResponse> edit(@PathVariable String chatFileId, @PathVariable String profileId, @Valid @RequestBody PersonaEditRequest request) {
        owned(chatFileId, profileId);
        return ApiResponse.ok(PersonaProfileResponse.from(edits.createDraft(profileId, request.draft())));
    }

    private PersonaProfile owned(String file, String id) {
        PersonaProfile p = profiles.selectById(id);
        if (p == null || !file.equals(p.getChatFileId())) throw new BizException(ErrorCode.PERSONA_PROFILE_NOT_FOUND);
        return p;
    }

    private void file(String id) {
        if (files.selectById(id) == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
    }
}
