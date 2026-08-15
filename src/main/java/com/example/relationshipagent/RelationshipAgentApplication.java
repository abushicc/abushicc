package com.example.relationshipagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 关系记忆与人格模拟 Agent — 启动入口。
 *
 * <p>阶段 1 完成：CSV 解析 → Message 入库 → 会话构建 → 统计缓存，
 * 阶段 2-5（向量化/记忆/人格/分析）后续迭代。
 */
@SpringBootApplication
@MapperScan("com.example.relationshipagent")  // 递归扫描所有子包中的 Mapper
@ConfigurationPropertiesScan("com.example.relationshipagent.config")
public class RelationshipAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelationshipAgentApplication.class, args);
    }
}
