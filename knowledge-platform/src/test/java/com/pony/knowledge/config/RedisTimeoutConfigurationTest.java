/* 验证 Redis 客户端从部署配置读取有上限的建连及命令等待。 */

package com.pony.knowledge.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTimeoutConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    var source = new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yml"))
                            .get(0);
                    context.getEnvironment().getPropertySources().addLast(source);
                } catch (Exception e) {
                    throw new IllegalStateException("Could not load application.yml", e);
                }
            });

    /** 覆盖超时、Redis的相关边界。通过 {@code contextRunner.run} 执行本例并核对输出或记录。 */
    @Test
    void redisClientUsesBoundedConnectionAndReadTimeoutsFromApplicationYaml() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
            assertThat(connectionFactory.getClientConfiguration().getCommandTimeout())
                    .isEqualTo(Duration.ofSeconds(2));

            ClientOptions clientOptions = connectionFactory.getClientConfiguration()
                    .getClientOptions().orElseThrow();
            assertThat(clientOptions.getSocketOptions().getConnectTimeout())
                    .isEqualTo(Duration.ofSeconds(1));
        });
    }
}
