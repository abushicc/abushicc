package com.example.relationshipagent.chatfile.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * chat_file 表 Mapper — MyBatis-Plus BaseMapper 提供开箱即用的 CRUD。
 */
@Mapper
public interface ChatFileRepository extends BaseMapper<ChatFile> {
}
