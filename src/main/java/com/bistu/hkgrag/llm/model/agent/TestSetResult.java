package com.bistu.hkgrag.llm.model.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "测试集结构")
public record TestSetResult(
        @JsonProperty(value = "question", required = true)
        @Schema(description = "问题", requiredMode = Schema.RequiredMode.REQUIRED)
        String question,

        @JsonProperty(value = "answer", required = true)
        @Schema(description = "答案", requiredMode = Schema.RequiredMode.REQUIRED)
        String answer,

        @JsonProperty(value = "according", required = true)
        @Schema(description = "问题和答案对应的文档原文", requiredMode = Schema.RequiredMode.REQUIRED)
        String according

) {
}
