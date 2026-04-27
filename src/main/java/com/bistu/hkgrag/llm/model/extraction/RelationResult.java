package com.bistu.hkgrag.llm.model.extraction;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型抽取实体的关系类
 * @param relationList 抽取出的关系列表
 */
public record RelationResult(
        @JsonProperty(value = "relations",required = true)
        List<Relation> relationList
) {
}
