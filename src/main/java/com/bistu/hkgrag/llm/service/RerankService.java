package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.llm.model.rerank.RerankResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RerankService {
    private final Logger log = LoggerFactory.getLogger(RerankService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("${HKG-RAG.rerank.url}")
    public  String url;
    @Value("${HKG-RAG.rerank.api-key}")
    public String api_key;
    @Value("${HKG-RAG.rerank.rerankModel}")
    public String rerankModel;
    @Value("${HKG-RAG.rerank.topK}")
    public int topK;

    public RerankService() {
        this.restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
    }

    /**
     * 通用 Rerank 调用
     */
    public List<DoFile> rerank(String query,
                               List<DoFile> documents,
                               RerankResponse.RerankType type) {
        try {
            // 1. 请求头（和 curl 完全一致）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + api_key);

            // 构建不同请求体
            Map<String, Object> requestBody = buildRequestBody(type, rerankModel, topK, query, documents);

            String response = restTemplate.postForObject(
                    url,
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
            // ✅ 统一解析
            List<RerankResponse.RerankResult> raw = parseResponse(response, type);
            int size = Math.min(topK, raw.size());
            List<RerankResponse.RerankResult> results = raw.subList(0, size);

            List<DoFile> sortedDocs = new ArrayList<>();
            for (RerankResponse.RerankResult result : results) {
                //保留重排序模型给出的相似度分数
                documents.get(result.getIndex()).setContentScore((float) result.getRelevance_score());
                sortedDocs.add(documents.get(result.getIndex()));
            }
            log.info("Rerank模型调用成功");
            return sortedDocs;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Rerank模型调用失败",e);
            return documents;
        }
    }

    /**
     * api和本地部署两种请求构建方式
     * @param type 请求方式
     * @param model 模型
     * @param topK 取前K个
     * @param query 匹配句子
     * @param documents 匹配文档
     * @return
     */
    private Map<String, Object> buildRequestBody(RerankResponse.RerankType type,
                                                 String model,
                                                 int topK,
                                                 String query,
                                                 List<DoFile> documents) {

        Map<String, Object> body = new HashMap<>();

        switch (type) {
            case DASHSCOPE -> {
                Map<String, Object> input = new HashMap<>();
                input.put("query", query);
                input.put("documents", documents.stream().map(DoFile::getContent).toList());

                Map<String, Object> parameters = new HashMap<>();
                parameters.put("return_documents", true);
                parameters.put("top_n", topK);

                body.put("model", model);
                body.put("input", input);
                body.put("parameters", parameters);
            }

            case LOCAL -> {
                body.put("query", query);
                body.put("documents", documents.stream().map(DoFile::getContent).toList());
            }
        }

        return body;
    }

    private List<RerankResponse.RerankResult> parseResponse(String json, RerankResponse.RerankType type) throws Exception {

        JsonNode root = objectMapper.readTree(json);

        switch (type) {

            case DASHSCOPE -> {
                JsonNode resultsNode = root.path("output").path("results");

                return objectMapper.convertValue(
                        resultsNode,
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class,
                                RerankResponse.RerankResult.class
                        )
                );
            }
            case LOCAL -> {
                JsonNode resultsNode = root.path("results");
                return objectMapper.convertValue(
                        resultsNode,
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class,
                                RerankResponse.RerankResult.class
                        )
                );
            }
        }
        throw new RuntimeException("未知的 rerank 类型");
    }


}
