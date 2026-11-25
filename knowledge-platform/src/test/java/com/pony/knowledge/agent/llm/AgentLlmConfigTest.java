/* 验证生产 Agent 和摘要 Bean 按内部网关配置进行装配。 */

package com.pony.knowledge.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.agent.memory.summary.GatewaySummaryService;
import com.pony.knowledge.agent.memory.summary.SummaryService;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.resilience.AiAvailabilityGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Retired dotenv/direct selection must not change production model execution. */
class AgentLlmConfigTest {
    /**
     * 协调 {@code AiAvailabilityGuard.disabled} 完成当前阶段，供后续处理使用。
     * @return 调用方使用的 {@code ApplicationContextRunner}
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withBean(ObjectMapper.class,ObjectMapper::new)
                .withBean(AiAvailabilityGuard.class,() -> AiAvailabilityGuard.disabled(new SimpleMeterRegistry()))
                .withBean(ModelRegistryService.class,() -> mock(ModelRegistryService.class))
                .withPropertyValues("ai.client.internal-token=test-internal-token")
                .withUserConfiguration(AgentLlmConfig.class,GatewaySummaryService.class);
    }

    /** 通过 {@code runner} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test void onlyGatewayDecisionAndSummaryBeansExist() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1,context.getBeansOfType(AgentLlm.class).size());
            assertInstanceOf(GatewayAgentLlm.class,context.getBean(AgentLlm.class));
            assertEquals(1,context.getBeansOfType(SummaryService.class).size());
            assertInstanceOf(GatewaySummaryService.class,context.getBean(SummaryService.class));
            assertTrue(context.getBeansOfType(LlmClient.class).isEmpty());
            assertTrue(context.getBeansOfType(AgentLlmProperties.class).isEmpty());
            assertEquals(35000,context.getBean(AgentGatewayProperties.class).getReadTimeoutMs());
        });
    }

    /** 通过 {@code runner} 驱动该场景，布尔状态符合断言，返回的数据、边界或顺序符合断言。 */
    @Test void oldDirectSwitchAndLegacyLlmVariablesCannotSelectAProductionModel() {
        runner().withPropertyValues("agent.llm.mode=direct","LLM_BASE_URL=http://unused.invalid","LLM_MODEL=legacy",
                "LLM_READ_TIMEOUT_MS=not-a-number").run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(GatewayAgentLlm.class,context.getBean(AgentLlm.class));
            assertInstanceOf(GatewaySummaryService.class,context.getBean(SummaryService.class));
            assertTrue(context.getBeansOfType(LlmClient.class).isEmpty());
        });
    }

    /** 通过 {@code runner} 驱动该场景，实际结果与固定预期一致。 */
    @Test void gatewayTransportUsesExplicitInternalServiceConfiguration() {
        runner().withPropertyValues("ai.client.base-url=http://127.0.0.1:8999","agent.llm.gateway.read-timeout-ms=45000")
                .run(context -> {
                    assertEquals("http://127.0.0.1:8999",context.getBean(AgentGatewayProperties.class).getBaseUrl());
                    assertEquals(45000,context.getBean(AgentGatewayProperties.class).getReadTimeoutMs());
                });
    }
}
