package com.example.relationshipagent.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * MyBatis-Plus 字段自动填充处理器。
 *
 * <p>insert 时自动填充 id（UUID v4）和 createdAt（当前 UTC 时间），
 * update 时自动填充 updatedAt。如果实体已手动设值则跳过。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        if (metaObject.hasGetter("id") && getFieldValByName("id", metaObject) == null) {
            setFieldValByName("id", UUID.randomUUID().toString(), metaObject);
        }
        Instant now = Instant.now();
        if (metaObject.hasGetter("createdAt")) {
            setFieldValByName("createdAt", now, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasGetter("updatedAt")) {
            setFieldValByName("updatedAt", Instant.now(), metaObject);
        }
    }
}
