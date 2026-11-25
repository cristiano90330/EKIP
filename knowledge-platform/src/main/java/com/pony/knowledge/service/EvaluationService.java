/* 约定单题、批量和基线评测服务的输入与输出。 */

package com.pony.knowledge.service;

import com.pony.knowledge.dto.EvaluationBatchRequestDTO;
import com.pony.knowledge.dto.EvaluationBatchResultDTO;
import com.pony.knowledge.dto.EvaluationRequestDTO;
import com.pony.knowledge.dto.EvaluationResultDTO;
import com.pony.knowledge.dto.RegressionCompareRequestDTO;
import com.pony.knowledge.evaluation.regression.RegressionVerdict;

public interface EvaluationService {

    /**
     * 基于标注相关 ID 评估检索候选并记录证据和耗时。
     * 上游为 EvaluationController.evaluateRetrieval。
     *
     * @param request 当前调用的请求参数
     * @return 单题检索、证据、生成评测指标及运行用量，以 EvaluationResultDTO 返回
     */
    EvaluationResultDTO evaluateRetrieval(EvaluationRequestDTO request);

    /**
     * 按请求用例运行检索或生成评测，再输出总体和分组结果。
     * 上游为 EvaluationController.evaluateBatch。
     *
     * @param request 当前调用的请求参数
     * @return 本次得到的 EvaluationBatchResultDTO 实例
     */
    EvaluationBatchResultDTO evaluateBatch(EvaluationBatchRequestDTO request);

    /**
     * 交给回归评估器计算两个批量评测结果的差异。
     * 上游为 EvaluationController.regressionCompare。
     *
     * @param request 当前调用的请求参数
     * @return 一次评测回归的整体判定和各指标差异，以 RegressionVerdict 返回
     */
    RegressionVerdict regressionCompare(RegressionCompareRequestDTO request);
}
