package com.bistu.hkgrag.llm.model.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型抽取的关系 record（LLM 响应的反序列化 record）
 * 注意：这是 LLM 抽取阶段的中间 record，不直接对应 Neo4j 的 Relation 类。
 * 不再依赖 LLM 返回 ID，只用实体名称来关联实体。
 * 不再使用谓词（predicate），关系含义完全由 description 的自然语言描述表达。
 *
 * @param sourceName   源实体名称
 * @param targetName   目标实体名称
 * @param sourceType   源实体类型
 * @param targetType   目标实体类型
 * @param description  关系的自然语言描述（即关系本身）
 * @param keywords     源实体名和目标实体名泛化后的关键词，用"+"连接，用于检索匹配
 */
@Schema(description = "关系信息")
public record Relation(
        @JsonProperty(value = "sourceName", required = true)
        @Schema(description = "源实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String sourceName,

        @JsonProperty(value = "targetName", required = true)
        @Schema(description = "目标实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String targetName,

        @JsonProperty(value = "sourceType", required = true)
        @Schema(description = "源实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String sourceType,

        @JsonProperty(value = "targetType", required = true)
        @Schema(description = "目标实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String targetType,

        @JsonProperty(value = "description", required = true)
        @Schema(description = "关系的自然语言描述，描述两个实体之间的关联", requiredMode = Schema.RequiredMode.REQUIRED)
        String description,

        @JsonProperty(value = "keywords")
        @Schema(description = "源实体名和目标实体名泛化后的关键词，用+连接")
        String keywords
) {
}
