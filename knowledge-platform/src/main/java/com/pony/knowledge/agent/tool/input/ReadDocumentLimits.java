/* 集中约束文档阅读工具的条数和字符数。 */

package com.pony.knowledge.agent.tool.input;

/**
 * read_document 的输入上限。独立成类是因为 ToolSchemaGenerator 会遍历输入类的所有声明字段：
 * 常量若放在 ReadDocumentInput 内，会作为 limit 之外的参数泄漏进 LLM schema。
 */
public final class ReadDocumentLimits {

    public static final int DEFAULT_LIMIT = 5;

    public static final int MAX_LIMIT = 10;

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ReadDocumentLimits() {
    }
}
