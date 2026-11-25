/* 把工具输入类型的字段与校验约束转换成模型可用的 JSON Schema。 */

package com.pony.knowledge.agent.tool;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool Schema 生成：从 ToolInput 类的字段派生 JSON-schema 参数定义，零漂移、零新依赖。
 *   - 字段类型 → JSON-schema type（string/integer/number/boolean/array）
 *   - @Size / @Max / @Min → 字符长度与数值边界（schema 与校验同一来源）
 *   - @NotBlank / @NotNull → required；非空白字符串同时声明长度下限与非空白模式
 * ToolInput 只声明 LLM 能控制的参数；身份/权限字段不存在于 inputClass，天然不进 schema。
 *
 * <p>这张映射表是手写的，「校验写了但 schema 没生成」这类漂移只能靠人补。
 * 已映射：字符串 @Size、@Max、@Min、@NotBlank、@NotNull。
 * 未映射：@Pattern、@Email、@DecimalMin —— 加注解前先确认这里有没有对应分支。
 */
public final class ToolSchemaGenerator {

    /** 保留私有构造入口，以便通过静态方法使用此类型。 */
    private ToolSchemaGenerator() {
    }

    /**
     * 读取输入类型字段与校验注解，生成模型可读的参数类型、必填项和边界。
     * 上游由 {@code KnowledgeAskTool.definition}、{@code KnowledgeSearchTool.definition}、{@code ListDocumentsTool.definition} 调用。
     * 下游调用 {@code field.isAnnotationPresent}、{@code max.value}、{@code min.value}。
     *
     * @param inputClass 本次调用传入的 {@code Class<?>}
     * @return 当前记录中的 {@code schema}
     */
    public static Map<String, Object> schemaOf(Class<?> inputClass) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Field field : inputClass.getDeclaredFields()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            Class<?> type = field.getType();

            if (type == String.class) {
                prop.put("type", "string");
                Size size = field.getAnnotation(Size.class);
                if (size != null) {
                    prop.put("maxLength", size.max());
                    if (size.min() > 0) {
                        prop.put("minLength", size.min());
                    }
                }
                if (field.isAnnotationPresent(NotBlank.class)) {
                    prop.put("minLength", Math.max(1, size == null ? 0 : size.min()));
                    prop.put("pattern", "\\S");
                }
            } else if (type == Integer.class || type == int.class
                    || type == Long.class || type == long.class) {
                prop.put("type", "integer");
                Max max = field.getAnnotation(Max.class);
                if (max != null) {
                    prop.put("maximum", max.value());
                }
                Min min = field.getAnnotation(Min.class);
                if (min != null) {
                    prop.put("minimum", min.value());
                }
            } else if (type == Double.class || type == double.class) {
                prop.put("type", "number");
            } else if (type == Boolean.class || type == boolean.class) {
                prop.put("type", "boolean");
            } else if (List.class.isAssignableFrom(type)) {
                prop.put("type", "array");
            } else {
                prop.put("type", "string");
            }

            if (field.isAnnotationPresent(NotBlank.class) || field.isAnnotationPresent(NotNull.class)) {
                required.add(field.getName());
            }
            properties.put(field.getName(), prop);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }
}
