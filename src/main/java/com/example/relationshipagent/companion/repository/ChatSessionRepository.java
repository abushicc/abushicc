package com.example.relationshipagent.companion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.companion.model.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionRepository extends BaseMapper<ChatSession> {
}
