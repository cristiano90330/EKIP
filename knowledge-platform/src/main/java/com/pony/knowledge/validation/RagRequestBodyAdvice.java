/* 在问答请求反序列化前检查 JSON 边界和请求体结构。 */

package com.pony.knowledge.validation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.pony.knowledge.controller.KnowledgeChatController;
import com.pony.knowledge.dto.RagChatRequestDTO;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** RAG HTTP 输入在绑定 DTO 前拒绝重复 JSON 字段，避免同一字段的先后值产生歧义。 */
@ControllerAdvice
public class RagRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private static final JsonFactory STRICT_JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * 判断当前 HTTP 参数是否需要启用严格 RAG JSON 检查。
     *
     * @param parameter 受当前请求体或注解约束的接口参数
     * @param targetType 本次处理的资源或反序列化目标类型
     * @param converterType Spring 选中的 HTTP 请求体转换器类型
     * @return 条件是否成立（判断当前 HTTP 参数是否需要启用严格 RAG JSON 检查）
     */
    @Override
    public boolean supports(MethodParameter parameter,
                            Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> bodyType = parameter.getParameterType();
        return bodyType == KnowledgeChatController.AskRequest.class
                || bodyType == RagChatRequestDTO.class;
    }

    /**
     * 预读并验证原始 JSON，请求有效时为后续反序列化重新提供请求体。
     *
     * @param inputMessage 本次操作所用的 HttpInputMessage 数据
     * @param parameter 受当前请求体或注解约束的接口参数
     * @param targetType 本次处理的资源或反序列化目标类型
     * @param converterType Spring 选中的 HTTP 请求体转换器类型
     * @throws IOException 底层流或响应无法读取或写入时
     * @return 本次计算或查询得到的 HttpInputMessage 结果
     */
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage,
                                           MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        // 预读请求流后将原内容重新包装供下游反序列化，避免后续读取已被消费的流。
        byte[] body = inputMessage.getBody().readAllBytes();
        try (JsonParser parser = STRICT_JSON_FACTORY.createParser(body)) {
            while (parser.nextToken() != null) {
                // Parse the complete object before normal DTO binding.
            }
        } catch (JsonProcessingException e) {
            throw new HttpMessageNotReadableException("请求体格式不正确", e, inputMessage);
        }

        return new HttpInputMessage() {
            /** 根据当前输入构建 InputStream 实例并交给调用方。 */
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            /** 计算当前流程使用的 getHeaders 结果并以 HttpHeaders 返回。 */
            @Override
            public HttpHeaders getHeaders() {
                return inputMessage.getHeaders();
            }
        };
    }
}
