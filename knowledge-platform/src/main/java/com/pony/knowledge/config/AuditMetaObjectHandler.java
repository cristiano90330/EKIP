/* 为 MyBatis Plus 插入和更新补充记录时间戳。 */

package com.pony.knowledge.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 更新时间元数据处理器：更新时由应用写入 update_time，创建时间继续使用数据库列默认值。 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * 在插入时补充创建和更新时间元数据。
     *
     * @param metaObject MyBatis 即将保存或更新的记录包装
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // 创建时间由数据库 DEFAULT 负责。
    }

    /**
     * 在更新前刷新记录的更新时间元数据。
     *
     * @param metaObject MyBatis 即将保存或更新的记录包装
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
