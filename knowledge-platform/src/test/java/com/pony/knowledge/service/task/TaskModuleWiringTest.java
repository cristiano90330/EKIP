/* 验证文档任务相关组件和统一投递入口在 Spring 中的装配。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.config.TaskReliabilityProperties;
import com.pony.knowledge.mapper.DocumentMapper;
import com.pony.knowledge.mapper.DocumentTaskMapper;
import com.pony.knowledge.modelmanagement.ModelRegistryService;
import com.pony.knowledge.service.DocumentChunkService;
import com.pony.knowledge.service.DocumentProcessService;
import com.pony.knowledge.service.ai.AiServiceClient;
import com.pony.knowledge.service.impl.DocumentProcessServiceImpl;
import com.pony.knowledge.utils.CurrentUserUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TaskModuleWiringTest {
    /** 通过 {@code queued.get} 驱动用例，核对 {@code coordinator.dispatch(7L)} 为 true、{@code coordinator.dispatch(7L)} 为 false。 */
    @Test
    void sharedCoordinatorResolvesRealWorkerWithoutCircularConstruction() {
        List<Runnable> queued = new ArrayList<>();
        DocumentTaskMapper tasks = mock(DocumentTaskMapper.class);
        new ApplicationContextRunner()
                .withUserConfiguration(TaskDispatchCoordinator.class, TaskCompletionService.class,
                        DocumentProcessServiceImpl.class, TaskLeaseHeartbeat.class)
                .withPropertyValues("minio.bucket-name=contract-bucket")
                .withBean("documentTaskExecutor", TaskExecutor.class, () -> queued::add)
                .withBean("taskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class))
                .withBean("documentTaskLeaseScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class))
                .withBean(DocumentMapper.class, () -> mock(DocumentMapper.class))
                .withBean(DocumentTaskMapper.class, () -> tasks)
                .withBean(DocumentChunkService.class, () -> mock(DocumentChunkService.class))
                .withBean(AiServiceClient.class, () -> mock(AiServiceClient.class))
                .withBean(ModelRegistryService.class, () -> mock(ModelRegistryService.class))
                .withBean(CurrentUserUtil.class, () -> mock(CurrentUserUtil.class))
                .withBean(TaskFailureClassifier.class, TaskFailureClassifier::new)
                .withBean(TaskReliabilityProperties.class, TaskReliabilityProperties::new)
                .withBean(TaskStatusEventBus.class, TaskStatusEventBus::new)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DocumentProcessService.class);
                    TaskDispatchCoordinator coordinator = context.getBean(TaskDispatchCoordinator.class);
                    assertThat(coordinator.dispatch(7L)).isTrue();
                    assertThat(coordinator.dispatch(7L)).isFalse();
                    queued.get(0).run(); // Missing mocked task ends normally, without AI/DB I/O.
                    verify(tasks).selectById(7L);
                    assertThat(coordinator.dispatch(7L)).isTrue();
                });
    }
}
