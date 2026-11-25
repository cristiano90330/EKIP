/* 验证页面响应的内容策略、来源策略与默认安全标头。 */

package com.pony.knowledge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
@AutoConfigureMockMvc
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    /** 向受保护端点发起匿名请求，核对返回的 CSP 包含预期的脚本、框架与对象限制。 */
    @Test
    void sendsContentSecurityPolicy() throws Exception {
        mockMvc.perform(get("/knowledge-base/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"));
    }

    /** 请求受保护端点，核对 Referrer-Policy 为 no-referrer。 */
    @Test
    void sendsReferrerPolicy() throws Exception {
        mockMvc.perform(get("/knowledge-base/list"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    /** 请求受保护端点，核对 nosniff 与框架限制标头仍存在。 */
    @Test
    void keepsDefaultNosniffAndFrameOptions() throws Exception {
        mockMvc.perform(get("/knowledge-base/list"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("X-Frame-Options"));
    }

    /** 请求允许匿名的健康探针，确认同样带有 CSP。 */
    @Test
    void sendsCspOnPermittedEndpointsToo() throws Exception {
        // 放行端点也要有安全头：health 在本环境可能返 503（下游依赖未齐），故不断言状态码
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().exists("Content-Security-Policy"));
    }
}
