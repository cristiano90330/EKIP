/* 验证文档处理任务状态及累计尝试次数的 API 字段映射。 */

package com.pony.knowledge.service.task;

import com.pony.knowledge.entity.DocumentTask;
import com.pony.knowledge.entity.DocumentTaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDtoConverterTest {

    /** 通过 {@code TaskDtoConverter.toDto} 驱动该场景，核对 {@code dto.getAutoRetryCount()} 等于 {@code 1}、{@code dto.getManualRetryCount()} 等于 {@code 2}。 */
    @Test
    void attemptAndLegacyRetryCountUseCumulativeClaims() {
        DocumentTask task = new DocumentTask();
        task.setStatus(DocumentTaskStatus.PROCESSING);
        task.setAutoRetryCount(1);
        task.setManualRetryCount(2);
        task.setTotalAttemptCount(8);

        var dto = TaskDtoConverter.toDto(task);

        assertThat(dto.getAutoRetryCount()).isEqualTo(1);
        assertThat(dto.getManualRetryCount()).isEqualTo(2);
        assertThat(dto.getAttempt()).isEqualTo(8);
        assertThat(dto.getRetryCount()).isEqualTo(8);
    }

    /** 通过 {@code TaskDtoConverter.toDto} 驱动该场景，核对 {@code dto.getAttempt()} 等于 {@code 3}、{@code dto.getRetryCount()} 等于 {@code 3}。 */
    @Test
    void missingCumulativeCountFallsBackToLegacyCounters() {
        DocumentTask task = new DocumentTask();
        task.setAutoRetryCount(2);
        task.setManualRetryCount(1);

        var dto = TaskDtoConverter.toDto(task);

        assertThat(dto.getAttempt()).isEqualTo(3);
        assertThat(dto.getRetryCount()).isEqualTo(3);
    }
}
