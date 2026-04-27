package com.bistu.hkgrag.llm.model.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型抽取实体的schema表定义
 * @param subject 主体名
 * @param predicate 属性名
 * @param object 客体名
 * @param subjectType 主体类型
 */
@Schema(description = "实体三元组信息")
public record Entity(
        @JsonProperty(value = "subject", required = true)
        @Schema(description = "主体实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String subject,

        @JsonProperty(value = "predicate", required = true)
        @Schema(description = "谓词（关系/属性）", requiredMode = Schema.RequiredMode.REQUIRED)
        String predicate,

        @JsonProperty(value = "object", required = true)
        @Schema(description = "客体实体名称或属性值", requiredMode = Schema.RequiredMode.REQUIRED)
        String object,

        @JsonProperty(value = "subjectType", required = true)
        @Schema(description = "主体类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String subjectType
) {
}
