/* 将页面策略值映射为检索版本和证据模式。 */

package com.pony.knowledge.service.rag;

/** Server-owned combinations exposed as per-question presets. */
public enum RagStrategy {
    DEFAULT(null, null),
    LIGHT("hybrid", "support"),
    RERANK_SUPPORT("hybrid-rerank", "support"),
    RERANK_SCORE("hybrid-rerank", "score");

    private final String version;
    private final String evidenceMode;

    /**
     * 为枚举值绑定供持久化或接口展示使用的编码。
     *
     * @param version 当前需要验证、记录或使用的版本
     * @param evidenceMode 初始化或更新同名字段的值
     */
    RagStrategy(String version, String evidenceMode) {
        this.version = version;
        this.evidenceMode = evidenceMode;
    }

    /**
     * 取得策略指定的检索版本，默认策略保留部署配置。
     * 上游为 PythonRagPipeline.prepareTrace、PythonRagPipeline.buildRequest。
     *
     * @param configured 本方法计算或判断使用的 configured 值
     * @return 请求指定的检索版本，默认策略时为部署配置版本
     */
    public String versionOr(String configured) { return version == null ? configured : version; }

    /**
     * 返回显式策略要求的证据准入模式。
     * 上游为 PythonRagPipeline.buildRequest。
     *
     * @return 策略指定的证据准入模式
     */
    public String evidenceMode() { return evidenceMode; }

    /**
     * 解析页面传入的策略值，非法值转为输入错误。
     * 上游为 KnowledgeChatController.ask、KnowledgeChatStreamController.askStream。
     *
     * @param value 待校验或转换的值
     * @return 本次得到的 RagStrategy 实例
     */
    public static RagStrategy fromRequest(String value) {
        if (value == null) return DEFAULT;
        return switch (value) {
            case "default" -> DEFAULT;
            case "light" -> LIGHT;
            case "rerank_support" -> RERANK_SUPPORT;
            case "rerank_score" -> RERANK_SCORE;
            default -> throw new IllegalArgumentException("未知问答策略");
        };
    }
}
