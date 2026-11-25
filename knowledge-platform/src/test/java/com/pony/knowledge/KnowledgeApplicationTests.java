/* 验证 Spring Boot 测试上下文的基本装配。 */

package com.pony.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "task.reliability.recovery-enabled=false")
class KnowledgeApplicationTests {

	/** 由 JUnit 启动 Spring Boot 测试上下文，装配过程中没有异常即通过。 */
	@Test
	void contextLoads() {
	}

}
