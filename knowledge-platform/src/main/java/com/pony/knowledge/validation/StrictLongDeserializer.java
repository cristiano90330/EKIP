/* 要求长整数参数使用合法整数 JSON 令牌并遵守数值范围。 */

package com.pony.knowledge.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/** 字段级 JSON 长整数输入；拒绝字符串、浮点数等隐式转换，保留解析器的溢出检查。 */
public final class StrictLongDeserializer extends JsonDeserializer<Long> {
    /**
     * 只接受整数 JSON 令牌并返回范围内的 Java 长整数。
     * 上游为 Jackson JSON 反序列化器。
     *
     * @param parser 提供 JSON 输入令牌的解析器
     * @param context 本次执行携带的上下文数据
     * @throws IOException 底层流或响应无法读取或写入时
     * @return 经过 JSON 令牌类型与取值范围检查的长整数
     */
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Long.class, "Expected a JSON integer");
        }
        return parser.getLongValue();
    }
}
