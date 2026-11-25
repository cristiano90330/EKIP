/* 将模型提供方枚举、协议解析和连通性测试转发至 AI 服务。 */

package com.pony.knowledge.modelmanagement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pony.knowledge.request.RequestIdContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Management preview and explicit tests use the same Python resolver as production. */
@Component
public class ModelRuntimeClient {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    /**
     * 保存将模型提供方枚举、协议解析和连通性测试转发至 AI 服务所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param mapper 操作本组件所需的数据记录的组件
     * @param baseUrl 下游接口地址
     * @param token 待验证或轮换的令牌
     */
    public ModelRuntimeClient(ObjectMapper mapper, @Value("${ai.client.base-url:http://127.0.0.1:8000}") String baseUrl,
                              @Value("${ai.client.internal-token}") String token) {
        this.mapper=mapper; this.baseUrl=baseUrl.replaceAll("/+$",""); this.token=token;
    }
    /**
     * 从 AI 模型端点读取提供方及配置说明。
     * 上游为 ModelRegistryService.providers。
     * 下游为 ModelRuntimeClient.call。
     *
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> providers() { return call("GET","/models/providers",null); }
    /**
     * 将模型草稿交给 AI 解析并取得协议与能力结果。
     * 上游为 ModelRegistryService.create、ModelRegistryService.update。
     * 下游为 ModelRuntimeClient.call。
     *
     * @param model 模型配置
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> resolve(Map<String,Object> model) { return call("POST","/models/resolve",Map.of("model",model)); }
    /**
     * 调用 AI 模型连接检查并返回探测结果。
     * 上游为 ModelRegistryService.testDraft、ModelRegistryService.testSaved。
     * 下游为 ModelRuntimeClient.call。
     *
     * @param model 模型配置
     * @param checks 本次操作所用的 Object 数据
     * @return 由本方法收集或转换的键值映射
     */
    public Map<String,Object> test(Map<String,Object> model, Object checks) {
        java.util.LinkedHashMap<String,Object> body = new java.util.LinkedHashMap<>();
        body.put("model",model); if (checks != null) body.put("checks",checks);
        return call("POST","/models/test",body);
    }
    /**
     * 附内部凭证调用 AI 模型管理端点，并映射响应或依赖异常。
     * 上游为 ModelRuntimeClient.providers、ModelRuntimeClient.resolve。
     * 下游为 RequestIdContext.current、ModelHttpDeadline.run。
     *
     * @param method 本方法计算或判断使用的 method 值
     * @param path 请求目标路径
     * @param body 调用方传入的请求体
     * @return 由本方法收集或转换的键值映射
     */
    private Map<String,Object> call(String method, String path, Map<String,Object> body) {
        try {
            String rid = RequestIdContext.current();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(35))
                    .header("X-Internal-Token",token).header("X-Request-ID",rid == null ? UUID.randomUUID().toString() : rid)
                    .header("Content-Type","application/json");
            if ("GET".equals(method)) builder.GET();
            else builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = ModelHttpDeadline.run(35000,
                    () -> http.send(builder.build(),HttpResponse.BodyHandlers.ofString()),null);
            if (response.statusCode() >= 400) {
                if (response.statusCode() == 400 || response.statusCode() == 422)
                    throw ModelManagementException.badRequest("模型配置或测试能力不符合所选协议，请检查运行配置");
                if (response.statusCode() == 504) throw new ModelManagementException(504,"model_timeout","模型测试超过总预算");
                throw ModelManagementException.unavailable("模型运行服务暂时不可用");
            }
            return mapper.readValue(response.body(),new TypeReference<>(){});
        } catch (ModelManagementException e) { throw e; }
        catch (java.net.http.HttpTimeoutException e) { throw new ModelManagementException(504,"model_timeout","模型请求超过总预算"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ModelManagementException.unavailable("模型请求已取消"); }
        catch (Exception e) { throw ModelManagementException.unavailable("模型运行服务暂时不可用"); }
    }
}
