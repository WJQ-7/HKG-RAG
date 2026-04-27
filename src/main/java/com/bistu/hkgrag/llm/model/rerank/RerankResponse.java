package com.bistu.hkgrag.llm.model.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
public class RerankResponse {

    private List<RerankResult> results;

    @Getter
    @Setter
    public static class RerankResult {
        private int index;
        private double relevance_score;
        private DocumentWrapper document;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocumentWrapper {
        private String text;
    }

    public enum RerankType {
        DASHSCOPE,
        LOCAL
    }
}
