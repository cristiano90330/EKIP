/* 验证用户更新时自动填充更新时间。 */

package com.pony.knowledge.mapper;

import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 无 ON UPDATE CURRENT_TIMESTAMP，update_time 改由 AuditMetaObjectHandler 填充。
 * 若填充失效，update_time 会永远停在 INSERT 时刻且不报错 —— 静默失效，没有任何异常可循。
 *
 * 刻意不加 @Transactional：PG 的 CURRENT_TIMESTAMP 返回的是「事务开始时刻」，
 * 整条用例挤在一个事务里时，DDL 默认值在语义上就分不出 INSERT 与 UPDATE，
 * 会把这个测试想验证的东西测糊。用例自己收尾（try/finally 删除探针行）。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
class UserUpdateTimeFillTest {

    @Autowired
    private UserMapper userMapper;

    /** 通过 {@code u.setUsername} 执行本例并核对输出或记录。 */
    @Test
    void updateTime_advancesOnUpdate() throws InterruptedException {
        User u = new User();
        u.setUsername("fill-probe-" + System.nanoTime());
        u.setPassword("x");
        u.setStatus(UserStatus.NORMAL);
        userMapper.insert(u);

        try {
            LocalDateTime afterInsert = userMapper.selectById(u.getId()).getUpdateTime();
            assertThat(afterInsert).isNotNull();

            Thread.sleep(1100);                      // 让 CURRENT_TIMESTAMP 的秒级分辨率能体现差异

            User patch = new User();
            patch.setId(u.getId());
            patch.setDepartment("changed");
            userMapper.updateById(patch);

            LocalDateTime afterUpdate = userMapper.selectById(u.getId()).getUpdateTime();
            assertThat(afterUpdate).isAfter(afterInsert);
            // 只断言 isAfter 还不够：万一 JVM 与 PG 的时区不一致，填充值会整体偏移若干小时，
            // 即使「没刷新」也能蒙混过关。加一个上界把「真的只过了 1.1 秒」钉住。
            assertThat(Duration.between(afterInsert, afterUpdate)).isLessThan(Duration.ofMinutes(1));
        } finally {
            userMapper.deleteById(u.getId());
        }
    }
}
