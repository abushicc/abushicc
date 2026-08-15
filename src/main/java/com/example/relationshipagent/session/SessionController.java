package com.example.relationshipagent.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.dto.PageResult;
import com.example.relationshipagent.common.dto.SessionDetailVo;
import com.example.relationshipagent.common.dto.SessionListItemVo;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat-files/{chatFileId}/sessions")
public class SessionController {

    private final ConversationSessionRepository sessionRepository;

    public SessionController(ConversationSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<SessionListItemVo>>> list(
            @PathVariable UUID chatFileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startTime,desc") String sort) {

        String cid = chatFileId.toString();
        LambdaQueryWrapper<ConversationSession> qw = new LambdaQueryWrapper<>();
        qw.eq(ConversationSession::getChatFileId, cid);
        if (sort.contains("desc")) qw.orderByDesc(ConversationSession::getStartTime);
        else qw.orderByAsc(ConversationSession::getStartTime);

        Page<ConversationSession> mpPage = new Page<>(page + 1, size);
        Page<ConversationSession> result = sessionRepository.selectPage(mpPage, qw);

        List<SessionListItemVo> items = result.getRecords().stream()
                .map(s -> new SessionListItemVo(s.getId(), s.getStartTime(), s.getEndTime(),
                        s.getMessageCount(), s.getSessionType(), s.getSummary())).toList();
        return ResponseEntity.ok(ApiResponse.ok(new PageResult<>(items, result.getTotal(), page, size)));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<SessionDetailVo>> detail(
            @PathVariable UUID chatFileId, @PathVariable UUID sessionId) {
        String cid = chatFileId.toString();
        String sid = sessionId.toString();
        ConversationSession session = sessionRepository.selectById(sid);
        if (session == null || !cid.equals(session.getChatFileId()))
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);

        return ResponseEntity.ok(ApiResponse.ok(new SessionDetailVo(
                session.getId(), session.getStartTime(), session.getEndTime(),
                session.getMessageCount(), session.getSessionType(), session.getSummary(),
                session.getFormattedText(), session.getSpeakerStats(),
                session.getDurationSeconds() != null ? session.getDurationSeconds() : 0)));
    }
}
