/* 接收问答知识库、问题、会话标识、引擎策略和截断数量。 */

package com.pony.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pony.knowledge.validation.RagInputRules;
import com.pony.knowledge.validation.RagQuestion;
import com.pony.knowledge.validation.StrictIntegerDeserializer;
import com.pony.knowledge.validation.StrictLongDeserializer;
import com.pony.knowledge.validation.StrictStringDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 指定知识库的会话问答输入，包含问题、会话标识、召回数量及可选编排引擎。 */
@Data
public class RagChatRequestDTO {
    /** 留空时使用服务默认引擎；显式提供的无效值会被拒绝。 */
    @Pattern(regexp = RagInputRules.PIPELINE_ENGINE_PATTERN,
            message = "pipelineEngine 只能是 fixed 或 langchain")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String pipelineEngine;

    @Pattern(regexp = RagInputRules.STRATEGY_PATTERN, message = "不支持该问答策略")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String strategy;

    @NotNull
    @Positive
    @JsonDeserialize(using = StrictLongDeserializer.class)
    private Long knowledgeBaseId;

    @RagQuestion
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String question;

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[\\p{L}\\p{N}._:-]+")    //只能包含：各国文字、各国数字、`.` `_` `:` `-`
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String conversationId;

    /** 本次请求的召回数量，范围 1–20，默认 5。 */
    @Min(RagInputRules.MIN_TOP_K)
    @Max(RagInputRules.MAX_TOP_K)
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer topK = RagInputRules.DEFAULT_TOP_K;
}
