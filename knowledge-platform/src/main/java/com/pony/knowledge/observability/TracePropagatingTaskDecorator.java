/* 为异步执行器提供观测与轨迹上下文传播能力。 */

package com.pony.knowledge.observability;

import com.pony.knowledge.request.RequestIdContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务上下文传播：提交时捕获 requestId 与当前 Observation，执行时恢复 requestId 并重开 observation scope。
 * 没有 Observation 时仍传播 requestId；有 Observation 时子线程日志与 span 延续父请求的上下文。
 */
@Configuration
public class TracePropagatingTaskDecorator {

    /**
     * 创建捕获并恢复 Micrometer 上下文的异步任务装饰器。
     * 上游为 Spring 容器组件装配。
     * 下游为 RequestIdContext.current、RequestIdContext.open。
     *
     * @param registry 本次操作所用的 ObservationRegistry 数据
     * @return 本次计算或查询得到的 TaskDecorator 结果
     */
    @Bean
    public TaskDecorator observationTaskDecorator(ObservationRegistry registry) {
        return task -> {
            Observation observation = registry.getCurrentObservation();
            String requestId = RequestIdContext.current();
            return () -> {
                try (RequestIdContext.Scope ignored = RequestIdContext.open(requestId)) {
                    if (observation == null) {
                        task.run();
                    } else {
                        try (Observation.Scope scope = observation.openScope()) {
                            task.run();
                        }
                    }
                }
            };
        };
    }
}
