/* 要求严格字符串字段使用 JSON 字符串令牌。 */

package com.pony.knowledge.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/** 字段级 JSON 字符串输入；禁止数字、布尔等隐式转换，null 交给字段自身的校验规则。 */
public final class StrictStringDeserializer extends JsonDeserializer<String> {
    /**
     * 只接受 JSON 字符串并返回其文本。
     * 上游为 Jackson JSON 反序列化器。
     *
     * @param parser 提供 JSON 输入令牌的解析器
     * @param context 本次执行携带的上下文数据
     * @throws IOException 底层流或响应无法读取或写入时
     * @return 输入 JSON 字符串的文本内容
     */
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(String.class, "Expected a JSON string");
        }
        return parser.getText();
    }
}
