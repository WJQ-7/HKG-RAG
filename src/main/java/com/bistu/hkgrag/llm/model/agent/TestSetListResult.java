package com.bistu.hkgrag.llm.model.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TestSetListResult(
        @JsonProperty(value = "testSetResultList",required = true)
        List<TestSetResult> testSetResultList
) {
}
