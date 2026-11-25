/* 验证账号 Mapper 对唯一标识和账号状态的读写。 */

package com.pony.knowledge.mapper;

import com.pony.knowledge.entity.User;
import com.pony.knowledge.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 走真实 PostgreSQL 的枚举往返映射验证（设计 §3.8 的已知地雷；改造前连的是 MySQL）。
 *
 * 为什么编译通过不算验证：@EnumValue 只对 MyBatis-Plus 自己生成的语句生效，
 * 而 UserMapper.selectByUsername 是手写 select *，走的是 MyBatis 自动映射，
 * 依赖 application.yml 的 mybatis-plus.configuration.default-enum-type-handler。
 * 上一轮把 handler 的包名写错（真实包名是 com.baomidou.mybatisplus.core.handlers），
 * 结果 17 个 @SpringBootTest 上下文一起崩，报错只说「属性绑定失败」，完全看不出是枚举的事。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@Transactional
class UserMapperIntegrationTest {

    @Autowired
    private UserMapper userMapper;

    /**
     * 向夹具写入记录，使用 {@code user.setUsername}、{@code user.setPassword}。
     * 由 {@code handWrittenSelectMapsDisabledStatusFromIntColumn}、{@code handWrittenSelectMapsNormalStatusFromIntColumn} 调用。
     * @param username 测试用户名
     * @param status 拟使用的状态值
     * 返回 {@code user}。
     */
    private User insert(String username, UserStatus status) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("$2a$10$notARealHashNotARealHashNotARealHashNotARealHa");
        user.setEmail(username + "@example.com");
        user.setDepartment("研发部");
        user.setStatus(status);
        userMapper.insert(user);
        return user;
    }

    /**
     * 构造 {@code String} 供测试使用。
     * 由 {@code handWrittenSelectMapsDisabledStatusFromIntColumn}、{@code handWrittenSelectMapsNormalStatusFromIntColumn} 调用。
     * @param prefix 生成唯一数据使用的前缀
     * 返回 {@code prefix + "-" + System.nanoTime()}。
     */
    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** 覆盖停用的相关边界。通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void handWrittenSelectMapsDisabledStatusFromIntColumn() {
        String username = unique("it-disabled");
        insert(username, UserStatus.DISABLED);

        User loaded = userMapper.selectByUsername(username);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(loaded.getStatus().getCode()).isZero();
    }

    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void handWrittenSelectMapsNormalStatusFromIntColumn() {
        String username = unique("it-normal");
        insert(username, UserStatus.NORMAL);

        User loaded = userMapper.selectByUsername(username);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(loaded.getStatus().getCode()).isEqualTo(1);
    }

    /**
     * 两条读取路径必须一致：BaseMapper 生成的语句（列清单由 MP 按 @EnumValue 生成）
     * 与手写 @Select 的 select *（走自动映射 + default-enum-type-handler）。
     */
    /** 通过 {@code userMapper.selectById} 执行本例并核对输出或记录。 */
    @Test
    void generatedAndHandWrittenQueriesAgreeOnTheEnum() {
        String username = unique("it-both-paths");
        User inserted = insert(username, UserStatus.DISABLED);

        assertThat(userMapper.selectById(inserted.getId()).getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(userMapper.selectByUsername(username).getStatus()).isEqualTo(UserStatus.DISABLED);
    }

    /**
     * 新映射的另外三列也要真的读得出来 —— 此前它们不在实体上，department 永远读不到，
     * create_time / update_time 只能靠直接连库看。
     */
    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void newlyMappedColumnsRoundTrip() {
        String username = unique("it-columns");
        User inserted = insert(username, UserStatus.NORMAL);

        User loaded = userMapper.selectByUsername(username);

        assertThat(loaded.getEmail()).isEqualTo(username + "@example.com");
        assertThat(loaded.getDepartment()).isEqualTo("研发部");
        // 时间列不在 Java 侧 set，交给 DDL 默认值；这里重新查一次库才拿得到
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(loaded.getUpdateTime()).isNotNull();
    }

    /**
     * 设计 §4.1 A3 要求核实的点：MyBatis-Plus 的 insertStrategy 默认 NOT_NULL，
     * 会把 null 字段排除在 INSERT 列清单之外，因此不会把 create_time 显式写成 NULL
     * 覆盖掉 DDL 的 DEFAULT CURRENT_TIMESTAMP。
     */
    /** 通过 {@code inserted.getCreateTime} 执行本例并核对输出或记录。 */
    @Test
    void insertWithoutExplicitTimestampsStillGetsDatabaseDefaults() {
        String username = unique("it-defaults");
        User inserted = insert(username, UserStatus.NORMAL);

        // insert 之后对象上仍是 null（这是项目既有约定的已知代价，不是 bug）
        assertThat(inserted.getCreateTime()).isNull();

        User reloaded = userMapper.selectById(inserted.getId());
        assertThat(reloaded.getCreateTime()).isNotNull();
        assertThat(reloaded.getUpdateTime()).isNotNull();
    }

    /**
     * register 显式 setStatus(NORMAL) 之后，值必须真的落进 int 列，
     * 而不是被写成枚举名字符串（那会因列是 int 直接报错或落 0 = 禁用）。
     */
    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void statusIsPersistedAsIntNotAsEnumName() {
        String username = unique("it-int-column");
        User inserted = insert(username, UserStatus.NORMAL);

        User loaded = userMapper.selectByUsername(username);

        // 若被写成枚举名，int 列会落 0 或插入直接失败；DISABLED 与 NORMAL 必须可区分
        assertThat(loaded.getStatus()).isNotEqualTo(UserStatus.DISABLED);
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(inserted.getId()).isNotNull();
    }

    /**
     * 登录/注册查重必须**大小写不敏感**：改造前 MySQL 的 utf8mb4_0900_ai_ci 让
     * `username = ?` 天然如此，PG 默认不是。V1__baseline.sql 的唯一索引 + 本 mapper 的
     * `lower(username) = lower(#{username})` 把语义补回来；这条覆盖「换个大小写就登不进来」那一半。
     * 注意返回的是**库里存的**大小写（与 MySQL 行为一致），不是用户输入的大小写。
     */
    /** 通过 {@code userMapper.selectByUsername} 执行本例并核对输出或记录。 */
    @Test
    void selectByUsername_isCaseInsensitive() {
        String username = unique("it-case");
        insert(username, UserStatus.NORMAL);

        User typedUpper = userMapper.selectByUsername(username.toUpperCase());
        User typedLower = userMapper.selectByUsername(username);

        assertThat(typedUpper).isNotNull();
        assertThat(typedLower).isNotNull();
        assertThat(typedUpper.getId()).isEqualTo(typedLower.getId());
        assertThat(typedUpper.getUsername()).isEqualTo(username);
    }

    /**
     * 用户名唯一性也必须大小写不敏感：Admin 与 admin 只能是同一个账号
     * （否则企业登录里出现两个只差大小写的账号，是明确的仿冒/混淆面）。
     * 唯一性由 V1__baseline.sql 的 `CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (lower(username))`
     * 保证 —— 若被退回 `UNIQUE (username)`，本用例立刻红。
     */
    /** 通过 {@code username.toUpperCase} 执行本例并核对输出或记录。 */
    @Test
    void usernameUniquenessIsCaseInsensitive() {
        String username = unique("it-uniq");
        insert(username, UserStatus.NORMAL);

        assertThatThrownBy(() -> insert(username.toUpperCase(), UserStatus.NORMAL))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uk_sys_user_username");
    }
}
