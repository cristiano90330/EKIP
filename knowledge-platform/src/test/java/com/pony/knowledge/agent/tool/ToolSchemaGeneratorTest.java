/* 验证模型工具输入 Schema 的校验约束与身份隔离。 */

package com.pony.knowledge.agent.tool;

import com.pony.knowledge.agent.tool.input.KnowledgeSearchInput;
import com.pony.knowledge.agent.tool.input.ReadDocumentInput;
import com.pony.knowledge.agent.tool.input.ToolInput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3.7c 验收：Schema 从 ToolInput 类自动派生（类型/required/校验约束），
 * 且不暴露任何身份/权限字段（原则 1：LLM 不能控制权限）。
 */
class ToolSchemaGeneratorTest {

    /** 通过 {@code ToolSchemaGenerator.schemaOf}、{@code schema.get} 驱动用例，布尔状态符合断言，实际结果与固定预期一致。 */
    @Test
    void readDocumentQuerySchemaRejectsEmptyAndWhitespaceGoals() {
        Map<String, Object> schema = ToolSchemaGenerator.schemaOf(ReadDocumentInput.class);
        Map<?, ?> query = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("query");

        assertEquals(1, query.get("minLength"));
        assertEquals(200, query.get("maxLength"));
        Pattern nonBlank = Pattern.compile((String) query.get("pattern"));
        assertFalse(nonBlank.matcher("").find());
        assertFalse(nonBlank.matcher(" \t\n").find());
        assertTrue(nonBlank.matcher("唯一核验代号").find());
    }

    /** 通过 {@code ToolSchemaGenerator.schemaOf}、{@code schema.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void schemaOf_derivesTypesRequiredAndMaxConstraints() {
        Map<String, Object> schema = ToolSchemaGenerator.schemaOf(KnowledgeSearchInput.class);

        assertEquals("object", schema.get("type"));

        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertEquals("string", ((Map<?, ?>) properties.get("query")).get("type"));
        assertEquals(200, ((Map<?, ?>) properties.get("query")).get("maxLength"));
        assertEquals("integer", ((Map<?, ?>) properties.get("topK")).get("type"));
        assertEquals(20L, ((Map<?, ?>) properties.get("topK")).get("maximum"));
        assertEquals(List.of("query"), schema.get("required"));
    }

    /** 通过 {@code ToolSchemaGenerator.schemaOf}、{@code schema.get} 驱动用例，实际结果与固定预期一致。 */
    @Test
    void schemaOf_derivesMinimum_fromMinAnnotation() {
        Map<String, Object> schema = ToolSchemaGenerator.schemaOf(KnowledgeSearchInput.class);
        Map<?, ?> topK = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("topK");

        assertEquals(1L, topK.get("minimum"), "@Min(1) 应映射为 minimum=1");
        assertEquals(20L, topK.get("maximum"), "上界同时保留");
    }

    /** 通过 {@code ToolSchemaGenerator.schemaOf}、{@code schema.get} 驱动用例，布尔状态符合断言。 */
    @Test
    void schemaOf_omitsMinimum_whenNoMinAnnotation() {
        Map<String, Object> schema = ToolSchemaGenerator.schemaOf(NoMinInput.class);
        Map<?, ?> value = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("value");

        assertFalse(value.containsKey("minimum"), "未声明 @Min 时不应生成 minimum");
    }

    /** 只带 @Max 的入参（供上一条测试）。 */
    static class NoMinInput implements ToolInput {
        @jakarta.validation.constraints.Max(5)
        private Integer value;
    }

    /** 为工具输入生成字段声明，核对模型看到的 Schema 中没有身份及权限字段。 */
    @Test
    void schemaOf_neverExposesIdentityOrPermissionFields() {
        Map<String, Object> schema = ToolSchemaGenerator.schemaOf(KnowledgeSearchInput.class);
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");

        assertFalse(properties.containsKey("userId"), "LLM schema 不能暴露 userId");
        assertFalse(properties.containsKey("accessibleKnowledgeBaseIds"), "LLM schema 不能暴露权限");
        assertFalse(properties.containsKey("knowledgeBaseIds"), "LLM schema 不能暴露 knowledgeBaseIds（越权字段）");
        assertEquals(2, properties.size(), "schema 只应包含 LLM 真正能控制的字段");
    }

    /** 构造输入与依赖返回值，核对输出和依赖调用。 */
    @Test
    void knowledgeSearchInput_hasNoPrivilegeField() {
        for (Field field : KnowledgeSearchInput.class.getDeclaredFields()) {
            assertNotEquals("knowledgeBaseIds", field.getName());
            assertNotEquals("knowledgeBaseId", field.getName());
            assertNotEquals("userId", field.getName());
            assertNotEquals("accessibleKnowledgeBaseIds", field.getName());
        }
    }
}
