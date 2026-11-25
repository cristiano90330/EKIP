/* 携带模型管理错误的 HTTP 状态和业务原因码。 */

package com.pony.knowledge.modelmanagement;

/** Safe model failures never retain upstream bodies or credentials. */
public class ModelManagementException extends RuntimeException {
    private final int status;
    private final String reasonCode;
    /**
     * 创建带状态和诊断信息的失败异常，供调用方映射响应。
     *
     * @param status 要读取或写入的状态
     * @param reasonCode 用于区分失败或降级路径的原因码
     * @param message 用于构建结果或异常的说明文字
     */
    public ModelManagementException(int status, String reasonCode, String message) {
        super(message); this.status = status; this.reasonCode = reasonCode;
    }
    /**
     * 返回本次失败的 HTTP 状态或记录状态。
     * 上游为 ModelManagementExceptionHandler.handle。
     *
     * @return 本次失败的 HTTP 状态或记录状态
     */
    public int getStatus() { return status; }
    /**
     * 返回定位执行结果的原因码。
     * 上游为 ModelManagementExceptionHandler.handle。
     *
     * @return 定位执行结果的原因码
     */
    public String getReasonCode() { return reasonCode; }
    /**
     * 构建报告模型管理输入无效的错误。
     * 上游为 ModelHttpDeadline.outerTimeout、ModelManagementController.credential。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 模型管理错误的 HTTP 状态和业务原因码，以 ModelManagementException 返回
     */
    public static ModelManagementException badRequest(String message) { return new ModelManagementException(400, "model_configuration_invalid", message); }
    /**
     * 构建报告指定模型管理资源不存在的错误。
     * 上游为 ModelRegistryService.resolveForPurpose、ModelRegistryService.requireOwned。
     *
     * @return 模型管理错误的 HTTP 状态和业务原因码，以 ModelManagementException 返回
     */
    public static ModelManagementException notFound() { return new ModelManagementException(404, "model_not_found", "模型不存在或无权访问"); }
    /**
     * 构建报告模型档案状态或版本冲突的错误。
     * 上游为 ModelRegistryService.update、ModelRegistryService.delete。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 模型管理错误的 HTTP 状态和业务原因码，以 ModelManagementException 返回
     */
    public static ModelManagementException conflict(String message) { return new ModelManagementException(409, "model_revision_conflict", message); }
    /**
     * 构建报告模型执行依赖不可用的错误。
     * 上游为 GatewaySummaryService.summarize、ModelCredentialCipher.encrypt。
     *
     * @param message 用于构建结果或异常的说明文字
     * @return 模型管理错误的 HTTP 状态和业务原因码，以 ModelManagementException 返回
     */
    public static ModelManagementException unavailable(String message) { return new ModelManagementException(503, "model_not_configured", message); }
}
