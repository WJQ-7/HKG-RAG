package com.bistu.hkgrag.llm.model.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型抽取实体的结果类（LLM 响应反序列化容器）
 * @param entityList 抽取出的实体列表
 */
public record EntityResult(
        @JsonProperty(value = "entities", required = true)
        List<Entity> entityList
) {
}
