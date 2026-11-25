/* 按 Unicode 字符数和空白规则校验问答文本。 */

package com.pony.knowledge.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 按 Python str.strip 的空白范围校验长度，补充平面字符按一个码点计数。 */
public final class RagQuestionValidator implements ConstraintValidator<RagQuestion, String> {
    /**
     * 按 Unicode 码点长度和与 AI 一致的空白约束校验问答输入。
     * 上游为 Bean Validation 参数校验。
     * 下游为 RagQuestionValidator.isPythonWhitespace。
     *
     * @param value 待校验或转换的值
     * @param context 本次执行携带的上下文数据
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        int start = 0;
        int end = value.length();

        while (start < end && isPythonWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isPythonWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return start < end
                && value.codePointCount(start, end) <= RagInputRules.MAX_QUESTION_CODE_POINTS;
    }

    /**
     * 识别会被 Python 问题校验视为空白的码点。
     * 上游为 RagQuestionValidator.isValid。
     *
     * @param codePoint 本方法计算或判断使用的 codePoint 值
     * @return 符合本方法描述的状态或完成条件时为 true
     */
    private static boolean isPythonWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000d)
                || (codePoint >= 0x001c && codePoint <= 0x0020)
                || codePoint == 0x0085 || codePoint == 0x00a0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000;
    }
}
