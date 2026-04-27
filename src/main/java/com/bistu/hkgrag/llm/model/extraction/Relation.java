package com.bistu.hkgrag.llm.model.extraction;

import com.bistu.hkgrag.dao.neo4j.model.Triple;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型抽取的关系schema表
 * @param subject 主实体
 * @param predicate 属性
 * @param object 客体
 * @param type 三元组类型
 * @param subjectId 主实体id
 * @param subjectType 主实体类型
 * @param objectId 客体id
 * @param objectType 客体类型
 */
@Schema(description = "关系三元组实体")
public record Relation(
        @JsonProperty(value = "subject", required = true)
        @Schema(description = "主实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String subject,

        @JsonProperty(value = "predicate", required = true)
        @Schema(description = "谓词（关系）", requiredMode = Schema.RequiredMode.REQUIRED)
        String predicate,

        @JsonProperty(value = "object", required = true)
        @Schema(description = "客体实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String object,

        @JsonProperty(value = "type", required = true)
        @Schema(description = "三元组类型：固定为 RELATION")
        Triple.Type type,

        @JsonProperty(value = "subjectId", required = true)
        @Schema(description = "主实体唯一ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String subjectId,

        @JsonProperty(value = "subjectType", required = true)
        @Schema(description = "主实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String subjectType,

        @JsonProperty(value = "objectId", required = true)
        @Schema(description = "客体实体唯一ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String objectId,

        @JsonProperty(value = "objectType", required = true)
        @Schema(description = "客体实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String objectType
) {
}
