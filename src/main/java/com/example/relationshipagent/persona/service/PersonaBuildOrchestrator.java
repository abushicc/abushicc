package com.example.relationshipagent.persona.service;

import com.example.relationshipagent.analysis.model.AgentRun;
import com.example.relationshipagent.analysis.service.AgentRunAuditService;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.memory.job.MemoryJobExecutor;
import com.example.relationshipagent.persona.agent.PersonaAgentClient;
import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.example.relationshipagent.persona.input.PersonaBuildInputService;
import com.example.relationshipagent.persona.validation.PersonaDraftValidator;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Explicit M7 Persona build task. It only creates DRAFT; human activation is separate.
 */
@Service
public class PersonaBuildOrchestrator {
    private final RelationshipAgentProperties props;
    private final ChatFileRepository files;
    private final PersonaBuildInputService inputs;
    private final ProcessingJobService jobs;
    private final MemoryJobExecutor executor;
    private final ObjectProvider<PersonaAgentClient> agent;
    private final PersonaDraftValidator validator;
    private final PersonaProfileWriter writer;
    private final AgentRunAuditService audits;

    public PersonaBuildOrchestrator(RelationshipAgentProperties props, ChatFileRepository files, PersonaBuildInputService inputs, ProcessingJobService jobs, MemoryJobExecutor executor, ObjectProvider<PersonaAgentClient> agent, PersonaDraftValidator validator, PersonaProfileWriter writer, AgentRunAuditService audits) {
        this.props = props;
        this.files = files;
        this.inputs = inputs;
        this.jobs = jobs;
        this.executor = executor;
        this.agent = agent;
        this.validator = validator;
        this.writer = writer;
        this.audits = audits;
    }

    public Accepted request(String chatFileId, String requestedTarget) {
        // Persona 构建只产生 DRAFT；ACTIVE 切换必须由单独的人工确认接口完成。
        if (!props.memory().enabled() || agent.getIfAvailable() == null)
            throw new BizException(ErrorCode.MEMORY_DISABLED);
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus()))
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING);
        String target = target(requestedTarget);
        PersonaBuildInput input = inputs.build(chatFileId, target);
        if (input.memories().isEmpty())
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING, "no approved Memory available for Persona build");
        String hash = ProcessingJobService.hashInput(chatFileId, target, props.memory().personaPromptVersion(), input.memories().stream().map(m -> m.getId() + "/" + m.getUpdatedAt()).reduce((a, b) -> a + "," + b).orElse(""), input.styleFingerprint().toString());
        ProcessingJob job = jobs.createOrGet(chatFileId, ProcessingJob.TYPE_PERSONA_BUILD, hash);
        if (job == null) return new Accepted(null, hash, ProcessingJob.STATUS_SUCCESS, true);
        if (jobs.tryTakeover(job.getId())) executor.submit(job.getId(), () -> run(job.getId(), input, hash));
        return new Accepted(job.getId(), hash, job.getStatus(), false);
    }

    private void run(String jobId, PersonaBuildInput input, String hash) {
        AgentRun audit = null;
        try {
            if (!jobs.isLeaseActive(jobId)) return;
            jobs.updateProgress(jobId, 0, 1);
            audit = audits.start(input.chatFileId(), props.analysis().provider(), props.analysis().model(), "PERSONA_BUILD", input.memories().size());
            // 先生成再做结构化校验；无效草稿不会进入 PersonaProfile，也不会影响当前 ACTIVE 版本。
            var generated = agent.getObject().generate(input);
            var checked = validator.validate(generated.draft(), input);
            if (!checked.valid())
                throw new IllegalArgumentException("Persona draft validation failed: " + String.join(",", checked.errors()));
            writer.write(input, hash, "persona-v1", props.memory().personaPromptVersion(), generated.response().model(), props.analysis().provider(), audit.getId(), checked);
            audits.success(audit, generated.response(), checked.features().size());
            jobs.updateProgress(jobId, 1, 1);
            jobs.markSuccess(jobId);
        } catch (Exception e) {
            audits.failed(audit, e);
            jobs.markFailed(jobId, safe(e));
        }
    }

    private String target(String requested) {
        String result = requested == null || requested.isBlank() ? props.memory().defaultTargetPerson() : requested;
        if (result == null || result.isBlank())
            throw new BizException(ErrorCode.MEMORY_TARGET_INVALID, "targetPerson is required");
        return result;
    }

    private static String safe(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + e.getMessage();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public record Accepted(String jobId, String inputHash, String status, boolean reused) {
    }
}
