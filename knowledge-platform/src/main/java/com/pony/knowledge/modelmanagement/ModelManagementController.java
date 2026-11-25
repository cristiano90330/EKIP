/* 提供模型登记、凭证、用途绑定与连接测试的用户接口。 */

package com.pony.knowledge.modelmanagement;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.utils.CurrentUserUtil;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Authenticated model management. Secrets are accepted only by explicit credential/test actions. */
@RestController
@RequestMapping("/models")
public class ModelManagementController {
    private final ModelRegistryService models;
    private final CurrentUserUtil users;
    /**
     * 保存提供模型登记、凭证、用途绑定与连接测试的用户接口所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param models ModelRegistryService，负责管理模型档案、用途绑定、凭证与执行配置的解析
     * @param users 本次操作使用的 CurrentUserUtil 组件或记录
     */
    public ModelManagementController(ModelRegistryService models,CurrentUserUtil users) { this.models=models; this.users=users; }
    /**
     * 列出当前账号的模型档案。
     * 上游为 HTTP GET /models。
     * 下游为 Result.success、ModelRegistryService.list。
     *
     * @return 包含 List&lt;Map&lt;String, Object&gt;&gt; 数据的业务响应
     */
    @GetMapping public Result<List<Map<String,Object>>> list() { return Result.success(models.list(users.getUserId())); }
    /**
     * 返回当前部署启用的内建默认模型。
     * 上游为 HTTP GET /models/defaults。
     * 下游为 Result.success、ModelRegistryService.deploymentDefaults。
     *
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @GetMapping("/defaults") public Result<Map<String,Object>> defaults() { return Result.success(models.deploymentDefaults()); }
    /**
     * 接收模型草稿并为当前账号创建档案。
     * 上游为 HTTP POST /models。
     * 下游为 Result.success、ModelRegistryService.create。
     *
     * @param draft 含当前操作所需属性的映射
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping public Result<Map<String,Object>> create(@RequestBody Map<String,Object> draft) { return Result.success(models.create(users.getUserId(),draft)); }
    /**
     * 按档案标识与预期版本更新模型配置。
     * 上游为 HTTP PUT /models/{id}。
     * 下游为 Result.success、ModelRegistryService.update。
     *
     * @param id 模型档案 ID
     * @param draft 含当前操作所需属性的映射
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PutMapping("/{id}") public Result<Map<String,Object>> update(@PathVariable String id,@RequestBody Map<String,Object> draft) {
        return Result.success(models.update(users.getUserId(),id,draft));
    }
    /**
     * 申请删除当前账号的模型档案。
     * 上游为 HTTP DELETE /models/{id}。
     * 下游为 ModelRegistryService.delete、Result.success。
     *
     * @param id 模型档案 ID
     * @param expectedRevision 调用方读取到并期望仍有效的版本
     * @return 包含本次操作状态的业务响应
     */
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable String id,@RequestParam long expectedRevision) {
        models.delete(users.getUserId(),id,expectedRevision); return Result.success(null);
    }
    /**
     * 为当前账号的模型设置新版访问凭证。
     * 上游为 HTTP PUT /models/{id}/credentials。
     * 下游为 ModelManagementException.badRequest、Result.success。
     *
     * @param id 模型档案 ID
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PutMapping("/{id}/credentials") public Result<Map<String,Object>> credential(@PathVariable String id,@RequestBody Map<String,Object> body) {
        if (!body.keySet().equals(java.util.Set.of("apiKey","expectedRevision")) || !(body.get("apiKey") instanceof String key))
            throw ModelManagementException.badRequest("凭证请求必须提供 apiKey 和 expectedRevision");
        return Result.success(models.credentials(users.getUserId(),id,ModelRegistryService.expected(body.get("expectedRevision")),key));
    }
    /**
     * 按版本清除已保存的模型访问凭证。
     * 上游为 HTTP DELETE /models/{id}/credentials。
     * 下游为 Result.success、ModelRegistryService.credentials。
     *
     * @param id 模型档案 ID
     * @param expectedRevision 调用方读取到并期望仍有效的版本
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @DeleteMapping("/{id}/credentials") public Result<Map<String,Object>> clearCredential(@PathVariable String id,@RequestParam long expectedRevision) {
        return Result.success(models.credentials(users.getUserId(),id,expectedRevision,null));
    }
    /**
     * 保存模型的证据或评分阈值校准。
     * 上游为 HTTP PUT /models/{id}/calibration。
     * 下游为 Result.success、ModelRegistryService.calibration。
     *
     * @param id 模型档案 ID
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PutMapping("/{id}/calibration") public Result<Map<String,Object>> calibration(@PathVariable String id,@RequestBody Map<String,Object> body) {
        return Result.success(models.calibration(users.getUserId(),id,body));
    }
    /**
     * 返回 AI 支持的模型提供方及协议元数据。
     * 上游为 HTTP GET /models/providers。
     * 下游为 Result.success、ModelRegistryService.providers。
     *
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @GetMapping("/providers") public Result<Map<String,Object>> providers() { users.getUserId(); return Result.success(models.providers()); }
    /**
     * 预览草稿设置规范化后的模型配置。
     * 上游为 HTTP POST /models/resolve。
     * 下游为 Result.success、ModelRegistryService.preview。
     *
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping("/resolve") public Result<Map<String,Object>> resolve(@RequestBody Map<String,Object> body) {
        users.getUserId(); return Result.success(models.preview(model(body)));
    }
    /**
     * 使用提交的草稿设置测试模型连接。
     * 上游为 HTTP POST /models/test。
     * 下游为 ModelManagementException.badRequest、Result.success。
     *
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping("/test") public Result<Map<String,Object>> test(@RequestBody Map<String,Object> body) {
        users.getUserId(); Object key=body.get("apiKey");
        if (key != null && !(key instanceof String)) throw ModelManagementException.badRequest("API Key 必须是字符串");
        return Result.success(models.testDraft(model(body),(String)key,body.get("checks")));
    }
    /**
     * 测试指定账号模型已有配置的连接。
     * 上游为 HTTP POST /models/{id}/test。
     * 下游为 Result.success、ModelRegistryService.testSaved。
     *
     * @param id 模型档案 ID
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PostMapping("/{id}/test") public Result<Map<String,Object>> testSaved(@PathVariable String id,@RequestBody(required=false) Map<String,Object> body) {
        return Result.success(models.testSaved(users.getUserId(),id,body == null ? null : body.get("checks")));
    }
    /**
     * 读取当前账号的模型用途绑定。
     * 上游为 HTTP GET /models/bindings。
     * 下游为 Result.success、ModelRegistryService.bindings。
     *
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @GetMapping("/bindings") public Result<Map<String,Object>> bindings() { return Result.success(models.bindings(users.getUserId())); }
    /**
     * 校验并更新当前账号的模型用途绑定。
     * 上游为 HTTP PUT /models/bindings。
     * 下游为 Result.success、ModelRegistryService.bind。
     *
     * @param body 调用方传入的请求体
     * @return 包含 Map&lt;String, Object&gt; 数据的业务响应
     */
    @PutMapping("/bindings") public Result<Map<String,Object>> bind(@RequestBody Map<String,Object> body) { return Result.success(models.bind(users.getUserId(),body)); }
    /**
     * 提取连接测试请求中实际提交的模型配置。
     * 上游为 ModelManagementController.resolve、ModelManagementController.test。
     * 下游为 ModelManagementException.badRequest。
     *
     * @param body 调用方传入的请求体
     * @return 由本方法收集或转换的键值映射
     */
    @SuppressWarnings("unchecked") private Map<String,Object> model(Map<String,Object> body) {
        if (!(body.get("model") instanceof Map<?,?> model)) throw ModelManagementException.badRequest("必须提供 model 对象");
        return (Map<String,Object>)model;
    }
}
