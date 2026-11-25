/* 承接检索评测、批量评测与基线对比的 HTTP 请求。 */

package com.pony.knowledge.controller;

import com.pony.knowledge.common.Result;
import com.pony.knowledge.dto.EvaluationBatchRequestDTO;
import com.pony.knowledge.dto.EvaluationBatchResultDTO;
import com.pony.knowledge.dto.EvaluationRequestDTO;
import com.pony.knowledge.dto.EvaluationResultDTO;
import com.pony.knowledge.dto.RegressionCompareRequestDTO;
import com.pony.knowledge.evaluation.regression.RegressionVerdict;
import com.pony.knowledge.service.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;

    /**
     * 保存承接检索评测、批量评测与基线对比的 HTTP 请求所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param evaluationService EvaluationService，负责约定单题、批量和基线评测服务的输入与输出
     */
    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 接收单题检索评测请求并返回指标结果。
     * 上游为 HTTP POST /evaluation/retrieval。
     * 下游为 Result.success、EvaluationService.evaluateRetrieval。
     *
     * @param request 当前调用的请求参数
     * @return 封装单题检索、证据、生成评测指标及运行用量的业务响应
     */
    @PostMapping("/retrieval")
    public Result<EvaluationResultDTO> evaluateRetrieval(
            @RequestBody EvaluationRequestDTO request) {
        return Result.success(evaluationService.evaluateRetrieval(request));
    }

    /**
     * 接收用例序列与 K 值，并返回分组和逐题评测。
     * 上游为 HTTP POST /evaluation/batch。
     * 下游为 Result.success、EvaluationService.evaluateBatch。
     *
     * @param request 当前调用的请求参数
     * @return 封装批量评测的总体、分组和逐条结果的业务响应
     */
    @PostMapping("/batch")
    public Result<EvaluationBatchResultDTO> evaluateBatch(
            @RequestBody EvaluationBatchRequestDTO request) {
        return Result.success(evaluationService.evaluateBatch(request));
    }

    /**
     * 按阈值对比两份评测结果并返回回归判定。
     * 上游为 HTTP POST /evaluation/regression。
     * 下游为 Result.success、EvaluationService.regressionCompare。
     *
     * @param request 当前调用的请求参数
     * @return 包含 RegressionVerdict 数据的业务响应
     */
    @PostMapping("/regression")
    public Result<RegressionVerdict> regressionCompare(@RequestBody RegressionCompareRequestDTO request) {
        return Result.success(evaluationService.regressionCompare(request));
    }
}
