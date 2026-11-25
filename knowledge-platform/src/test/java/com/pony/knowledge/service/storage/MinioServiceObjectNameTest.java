/* 验证对象存储使用的文件名与扩展名生成规则。 */

package com.pony.knowledge.service.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 升级项 P1-9：对象 key 从「UUID + 原始文件名」改成「UUID + 扩展名」，
 * 保证 key 恒为安全字符集，不受中文名、空格、路径分隔符影响。
 */
class MinioServiceObjectNameTest {

    /** 通过 {@code MinioService.objectNameOf} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void keepsOnlyUuidAndExtension() {
        String objectName = MinioService.objectNameOf("C:\\temp\\产品 说明 v2.pdf");

        assertThat(objectName).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(objectName).doesNotContain(" ", "\\", "/", "产品");
    }

    /** 通过 {@code MinioService.objectNameOf} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void sameFileNameProducesDifferentKeys() {
        assertThat(MinioService.objectNameOf("手册.pdf"))
                .isNotEqualTo(MinioService.objectNameOf("手册.pdf"));
    }

    /** 通过 {@code MinioService.objectNameOf} 驱动该场景，核对输出和依赖调用。 */
    @Test
    void nullAndExtensionlessNamesProduceSafeKeys() {
        // 改造前 null 文件名会得到 "...-null" 这种 key
        assertThat(MinioService.objectNameOf(null)).matches("[0-9a-f\\-]{36}");
        assertThat(MinioService.objectNameOf("README")).doesNotContain(".");
    }
}
