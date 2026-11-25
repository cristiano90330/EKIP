/* 验证问答请求只接受服务端签发的非空会话标识。 */

package com.pony.knowledge.agent.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** 通过 {@code validator.validate} 驱动用例，返回的数据、边界或顺序符合断言。 */
    @Test
    void 缺少sessionId被拒绝() {
        AgentRequest request = AgentRequest.builder().question("你能做什么").build();

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("sessionId");
    }

    /** 通过 {@code validator.validate} 驱动用例，返回的数据、边界或顺序符合断言。 */
    @Test
    void 空白和旧客户端自定义ID被拒绝() {
        for (String id : java.util.List.of("   ", "legacy-client-id", "as_invalid")) {
            AgentRequest request = AgentRequest.builder().question("你能做什么").sessionId(id).build();
            assertThat(validator.validate(request))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("sessionId");
        }
    }

    /** 通过 {@code validator.validate} 驱动用例，核对 {@code validator.validate(request)} 为空。 */
    @Test
    void 带会话ID的请求通过校验() {
        AgentRequest request = AgentRequest.builder()
                .question("你能做什么")
                .sessionId("as_123e4567-e89b-12d3-a456-426614174000")
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }
}
