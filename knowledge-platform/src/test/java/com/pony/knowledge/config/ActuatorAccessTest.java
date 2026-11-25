/* 核对管理探针、指标与日志端点各自的匿名访问范围。 */

package com.pony.knowledge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 升级项 02 章 3.1 P1-15：/actuator/** 从全放行改为「观测面匿名可达、管理面要认证」。
 *
 * 断言刻意只围绕「有没有被认证拦下」，不围绕业务状态码——health 在本环境会因下游依赖缺失返 503、
 * prometheus 与 env 在 MockMvc 下返 500，这些都是端点自身可用性问题，与本条要守的边界无关。
 *
 * 前端观测页走 api/http.ts 的 raw 实例、请求拦截器已带 Authorization，所以收严不影响它；
 * 真正关掉的是匿名改日志级别（loggers）与读配置值（env）这两个未授权入口。
 */
@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@AutoConfigureMockMvc
class ActuatorAccessTest {

    @Autowired
    private MockMvc mockMvc;

    /** 覆盖匿名的相关边界。通过 {@code statusOf} 执行本例并核对输出或记录。 */
    @Test
    void observabilityEndpointsStayAnonymous() throws Exception {
        assertThat(statusOf("/actuator/health")).isNotEqualTo(401);
        assertThat(statusOf("/actuator/info")).isNotEqualTo(401);
        assertThat(statusOf("/actuator/prometheus")).isNotEqualTo(401);
    }

    /** 覆盖认证的相关边界。通过 {@code statusOf} 执行本例并核对输出或记录。 */
    @Test
    void loggersRequireAuthentication() throws Exception {
        // 改造前这里匿名返回 200 + 全部 logger 清单，任何人都能运行时改日志级别
        assertThat(statusOf("/actuator/loggers")).isEqualTo(401);
    }

    /** 覆盖认证、指标的相关边界。通过 {@code statusOf} 执行本例并核对输出或记录。 */
    @Test
    void metricsRequireAuthentication() throws Exception {
        assertThat(statusOf("/actuator/metrics")).isEqualTo(401);
    }

    /** 核对 HTTP 401。 */
    @Test
    void anonymousJwtRequestStillRejected() throws Exception {
        mockMvc.perform(get("/knowledge-base/list")).andExpect(status().isUnauthorized());
    }

    /**
     * 执行指定路径的匿名请求并返回 HTTP 状态。
     * 由 {@code observabilityEndpointsStayAnonymous}、{@code loggersRequireAuthentication} 调用。
     * @param uri 本用例传入的{@code uri}
     */
    private int statusOf(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andReturn().getResponse().getStatus();
    }
}
