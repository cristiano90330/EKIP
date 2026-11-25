/* 声明由问答文本约束校验器检查的 Bean Validation 注解。 */

package com.pony.knowledge.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** 与 Python RAG 入口一致：去除首尾空白后非空，且最多 4000 个 Unicode 码点。 */
@Documented
@Constraint(validatedBy = RagQuestionValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface RagQuestion {
    /** 定义问答文本校验失败时框架读取的提示模板。 */
    String message() default "问题不能为空，且去除首尾空白后最多4000个字符";

    /** 指定问答文本约束适用的 Bean Validation 分组。 */
    Class<?>[] groups() default {};

    /** 携带 Bean Validation 可读取的约束附加信息。 */
    Class<? extends Payload>[] payload() default {};
}
