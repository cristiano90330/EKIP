/* 绑定登录限流开关、允许失败次数与锁定窗口。 */

package com.pony.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录限流配置（application.yml 的 login.throttle.* 段）。
 * 用 @ConfigurationProperties 承接而不是散落的 @Value，与 TaskReliabilityProperties 同一风格。
 * 默认值与 yml 一致，便于测试里单独 new 出来用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "login.throttle")
public class LoginThrottleProperties {

    /**
     * 总开关。false 时 LoginThrottle 三个方法全部 no-op ——
     * 登录相关的集成测试靠它避免「被自己上一轮的失败计数锁住」。
     */
    private boolean enabled = true;

    /** 同一 (username, ip) 允许的连续失败次数；第 maxAttempts+1 次请求直接被拒（不跑 BCrypt）。 */
    private int maxAttempts = 5;

    /** 锁定窗口（分钟），也是 Redis 计数键的 TTL。 */
    private int lockMinutes = 15;
}
