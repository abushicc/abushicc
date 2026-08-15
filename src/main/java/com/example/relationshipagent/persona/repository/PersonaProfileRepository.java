package com.example.relationshipagent.persona.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.persona.model.PersonaProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PersonaProfileRepository extends BaseMapper<PersonaProfile> {
}
