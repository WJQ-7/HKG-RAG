package com.bistu.hkgrag.Datasets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
public class WixQA {
    private static final Logger log = LoggerFactory.getLogger(WixQA.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void WixQAExtract() throws Exception {
        String inputContentPath = "src/main/resources/data/datasets/WixQA/wix_kb_corpus.jsonl";
        String inputQAPath = "src/main/resources/data/datasets/WixQA/test.jsonl";
        String outputWixQAPath = "src/main/resources/data/datasets/WixQA/test_1.jsonl";
        int size = 1;

        // 1. 读取所有 QA 对
        List<Map<String, Object>> allQAs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputQAPath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> qa = objectMapper.readValue(line, Map.class);
                allQAs.add(qa);
            }
        }
        log.info("共读取 {} 条 QA 数据", allQAs.size());

        // 2. 随机抽取 500 个 QA 对
        Collections.shuffle(allQAs, new Random(42));
        List<Map<String, Object>> sampledQAs = allQAs.subList(0, Math.min(size, allQAs.size()));

        // 3. 收集所有需要的 article_ids
        Set<String> neededArticleIds = new HashSet<>();
        for (Map<String, Object> qa : sampledQAs) {
            List<String> articleIds = (List<String>) qa.get("article_ids");
            if (articleIds != null) {
                neededArticleIds.addAll(articleIds);
            }
        }
        log.info("需要匹配 {} 个唯一 article_id", neededArticleIds.size());

        // 4. 读取文章内容，构建 id -> content 的映射（只读需要的）
        Map<String, Map<String, Object>> articleMap = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputContentPath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> article = objectMapper.readValue(line, Map.class);
                String id = (String) article.get("id");
                if (neededArticleIds.contains(id)) {
                    articleMap.put(id, article);
                }
                // 如果已经找齐了，提前退出
                if (articleMap.size() == neededArticleIds.size()) {
                    break;
                }
            }
        }
        log.info("成功匹配 {} 篇文章内容", articleMap.size());

        // 5. 组装输出：每个 QA 对带上对应的文章内容
        List<Map<String, Object>> outputList = new ArrayList<>();
        for (Map<String, Object> qa : sampledQAs) {
            Map<String, Object> outputItem = new LinkedHashMap<>();
            outputItem.put("question", qa.get("question"));
            outputItem.put("answer", qa.get("answer"));
            List<String> articleIds = (List<String>) qa.get("article_ids");
            List<Map<String, Object>> matchedArticles = new ArrayList<>();
            if (articleIds != null) {
                for (String articleId : articleIds) {
                    Map<String, Object> article = articleMap.get(articleId);
                    if (article != null) {
                        matchedArticles.add(article);
                    } else {
                        log.warn("未找到 article_id: {}", articleId);
                    }
                }
            }
            outputItem.put("articles", matchedArticles);
            outputItem.put("article_ids", articleIds);
            outputList.add(outputItem);
        }

        // 6. 写入输出文件
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputWixQAPath), StandardCharsets.UTF_8)) {
            for (Map<String, Object> item : outputList) {
                writer.write(objectMapper.writeValueAsString(item));
                writer.newLine();
            }
        }
        log.info("成功输出 {} 条数据到 {}", outputList.size(), outputWixQAPath);
    }
}
