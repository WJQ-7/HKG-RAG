package com.bistu.hkgrag.llm.model.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型抽取实体的 schema 表定义（LLM 响应的反序列化 record）
 * 注意：这是 LLM 抽取阶段的中间 record，不直接对应 Neo4j 的 Entity 类
 * @param entityName        实体名称
 * @param entityType        实体类型
 * @param entityDescription 实体自然语言描述
 * @param entityInfo        实体结构化属性信息（文档节点存发文机构/时间等，内容实体存职务/联系方式/所属组织等）
 */
@Schema(description = "实体信息")
public record Entity(
        @JsonProperty(value = "entityName", required = true)
        @Schema(description = "实体名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String entityName,

        @JsonProperty(value = "entityType", required = true)
        @Schema(description = "实体类型，从[人物, 技术, 任务, 组织, 地点, 事件, 文档]中选择", requiredMode = Schema.RequiredMode.REQUIRED)
        String entityType,

        @JsonProperty(value = "entityDescription", required = true)
        @Schema(description = "实体的自然语言描述，包含该实体在文档中的核心属性和上下文信息", requiredMode = Schema.RequiredMode.REQUIRED)
        String entityDescription,

        @JsonProperty(value = "entityInfo")
        @Schema(description = "实体结构化属性信息，格式为 key1:value1; key2:value2，内容实体存储职务、联系方式、所属组织等属性")
        String entityInfo
) {
}
