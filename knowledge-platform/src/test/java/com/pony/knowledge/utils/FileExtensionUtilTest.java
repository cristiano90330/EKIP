/* 核对文件名扩展名提取对缺失、大小写和多点名称的处理。 */

package com.pony.knowledge.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 升级项 P0-3 / P1-9 共用的扩展名提取。
 * 覆盖 2.3(i) 列出的三个隐患输入：null、带路径分隔符、含特殊字符。
 */
class FileExtensionUtilTest {

    /** 通过 {@code FileExtensionUtil.of} 执行本例并核对输出或记录。 */
    @Test
    void extractsLowercaseExtension() {
        assertThat(FileExtensionUtil.of("手册.pdf")).isEqualTo("pdf");
        assertThat(FileExtensionUtil.of("REPORT.PDF")).isEqualTo("pdf");
        assertThat(FileExtensionUtil.of("a.tar.gz")).isEqualTo("gz");
    }

    /** 通过 {@code FileExtensionUtil.of} 执行本例并核对输出或记录。 */
    @Test
    void ignoresPathPrefixEvenWhenDirectoryContainsDot() {
        // 目录名带点时，不先剥路径就会把 "2/manual" 当成扩展名
        assertThat(FileExtensionUtil.of("C:\\Users\\x\\手册.pdf")).isEqualTo("pdf");
        assertThat(FileExtensionUtil.of("/v1.2/manual.pdf")).isEqualTo("pdf");
        assertThat(FileExtensionUtil.of("/v1.2/manual")).isEmpty();
    }

    /** 覆盖空值的相关边界。通过 {@code FileExtensionUtil.of} 执行本例并核对输出或记录。 */
    @Test
    void returnsEmptyForUnusableNames() {
        assertThat(FileExtensionUtil.of(null)).isEmpty();
        assertThat(FileExtensionUtil.of("")).isEmpty();
        assertThat(FileExtensionUtil.of("README")).isEmpty();
        assertThat(FileExtensionUtil.of("no-extension-at-end.")).isEmpty();
        // 扩展名里含空格 / 斜杠一律视为不可信输入
        assertThat(FileExtensionUtil.of("a.p df")).isEmpty();
        assertThat(FileExtensionUtil.of("a.pd/f")).isEmpty();
    }
}
