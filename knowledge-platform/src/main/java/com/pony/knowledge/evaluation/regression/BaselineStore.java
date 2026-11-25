/* 读写回归评测使用的本地 JSON 指标基线。 */

package com.pony.knowledge.evaluation.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.dto.MetricAggregateDTO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基线聚合指标的简单 JSON 文件存储（3.9g）。默认目录 eval/reports，按版本分文件，幂等覆盖。
 */
public class BaselineStore {

    private final ObjectMapper objectMapper;

    private final Path defaultDir;

    /** 用该入口的默认选项初始化 BaselineStore 实例。 */
    public BaselineStore() {
        this(new ObjectMapper(), Paths.get("eval", "reports"));
    }

    /**
     * 保存读写回归评测使用的本地 JSON 指标基线所需的依赖和初始配置。
     *
     * @param objectMapper 序列化和解析 JSON 使用的组件
     * @param defaultDir 本次操作所用的 Path 数据
     */
    public BaselineStore(ObjectMapper objectMapper, Path defaultDir) {
        this.objectMapper = objectMapper;
        this.defaultDir = defaultDir;
    }

    /**
     * 以 JSON 格式持久化指定名称的评测基线。
     * 下游为 Files.createDirectories、Files.writeString。
     *
     * @param baseline 用于比较的基线指标
     * @param version 当前需要验证、记录或使用的版本
     * @throws IOException 底层流或响应无法读取或写入时
     */
    public void save(MetricAggregateDTO baseline, String version) throws IOException {
        Files.createDirectories(defaultDir);
        Files.writeString(pathFor(version),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(baseline));
    }

    /**
     * 读取指定名称的基线并反序列化为评测聚合。
     * 下游为 Files.exists。
     *
     * @param version 当前需要验证、记录或使用的版本
     * @throws IOException 底层流或响应无法读取或写入时
     * @return 评测的成功与失败分布、质量、耗时和用量汇总，以 MetricAggregateDTO 返回
     */
    public MetricAggregateDTO load(String version) throws IOException {
        Path p = pathFor(version);
        if (!Files.exists(p)) {
            return null;
        }
        return objectMapper.readValue(p.toFile(), MetricAggregateDTO.class);
    }

    /**
     * 根据基线目录和名称建立用于读写的路径。
     * 上游为 BaselineStore.save、BaselineStore.load。
     *
     * @param version 当前需要验证、记录或使用的版本
     * @return 本次计算或查询得到的 Path 结果
     */
    private Path pathFor(String version) {
        String safe = version == null || version.isBlank() ? "default" : version;
        return defaultDir.resolve("baseline_" + safe + ".json");
    }
}
