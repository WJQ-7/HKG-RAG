package com.bistu.hkgrag.Datasets;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * UltraDomain 数据集提取器
 * 将 agriculture/cs/legal/mix 的 JSONL 数据集转换为 HKG-RAG 的 DoFile 分块格式
 * <p>
 * UltraDomain 原始格式：每行一个 JSON 对象
 * { "input": "问题", "answers": ["答案"], "context": "长文档全文", "label": "agriculture", "context_id": "..." }
 * <p>
 * 输出格式（chunk JSONL）：
 * { "fileId": "UUID", "chunkId": "MD5", "fileName": "文档名", "topic": "", "content": "分块文本" }
 *
 * @author wangjingqi
 */
@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class UltraDomainDataset {
    private static final Logger log = LoggerFactory.getLogger(UltraDomainDataset.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${HKG-RAG.chunk.max-size}")
    int maxChunkSize;

    @Value("${HKG-RAG.chunk.overlap-chunk-size}")
    int overlapChunkSize;

    public void setMaxChunkSize(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public void setOverlapChunkSize(int overlapChunkSize) {
        this.overlapChunkSize = overlapChunkSize;
    }

    /**
     * 将 agriculture.jsonl 转换为分块后的 chunk JSONL
     * <p>
     * 流程：
     * 1. 逐行读取 agriculture.jsonl，提取 context（长文档全文）
     * 2. 为每个 context 创建 DoFile（fileId=context_id, fileName=meta.title）
     * 3. 调用 TextUtils.chunk() 进行滑动重叠分块
     * 4. 输出 agriculture_chunk.jsonl（供后续入库使用）
     * 5. 同时输出 agriculture_qa.jsonl（问答对，供评估使用）
     */
    @Test
    public void extractAgriculture() throws Exception {
        extractDomain("agriculture");
    }

    @Test
    public void extractCS() throws Exception {
        extractDomain("cs");
    }

    @Test
    public void extractLegal() throws Exception {
        extractDomain("legal");
    }

    @Test
    public void extractMix() throws Exception {
        extractDomain("mix");
    }

    /**
     * 通用领域提取方法
     */
    public void extractDomain(String domain) throws Exception {
        String domainDir = "src/main/resources/data/datasets/UltraDomain/" + domain + "/";
        new java.io.File(domainDir).mkdirs();

        String inputPath = "src/main/resources/data/datasets/UltraDomain/" + domain + ".jsonl";
        String chunkOutputPath = domainDir + "chunk.jsonl";
        String qaOutputPath = domainDir + "qa.jsonl";

        log.info("========== 开始处理 UltraDomain/{} 数据集 ==========", domain);
        log.info("配置: maxChunkSize={}, overlapChunkSize={}", maxChunkSize, overlapChunkSize);

        // Step1: 读取原始数据集
        List<Map<String, Object>> rawData = new ArrayList<>();
        Map<String, DoFile> contextFileMap = new LinkedHashMap<>(); // context_id -> DoFile，去重

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputPath), StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                @SuppressWarnings("unchecked")
                Map<String, Object> record = objectMapper.readValue(line, Map.class);
                rawData.add(record);

                // 兼容两种格式：
                //   agriculture/cs: 有 context_id，多个QA共享同一长文档，需去重
                //   legal/mix:      只有 _id，每个QA是独立短文档，无需去重
                String contextId = (String) record.get("context_id");
                if (contextId == null) {
                    contextId = (String) record.get("_id");  // fallback
                }
                if (contextId == null) {
                    contextId = "doc_" + lineNum;  // 兜底
                }

                // 去重：同一 context_id 只创建一次 DoFile
                if (!contextFileMap.containsKey(contextId)) {
                    DoFile doFile = new DoFile();
                    doFile.setFileId(contextId);

                    // 文件名：优先 meta.title，其次 dataset，最后用 contextId
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) record.get("meta");
                    String title;
                    if (meta != null && meta.get("title") != null) {
                        title = (String) meta.get("title");
                    } else if (record.get("dataset") != null) {
                        title = (String) record.get("dataset");
                    } else {
                        title = contextId;
                    }
                    doFile.setFileName(title);

                    String context = (String) record.get("context");
                    doFile.setContent(context != null ? context : "");
                    doFile.setTopic("");

                    contextFileMap.put(contextId, doFile);
                }
            }
        }

        log.info("读取完成: {} 条原始记录, {} 个唯一文档", rawData.size(), contextFileMap.size());

        // Step2: 对每个文档进行分块
        List<DoFile> docList = new ArrayList<>(contextFileMap.values());
        List<DoFile> chunkedFiles = TextUtils.chunk(docList, maxChunkSize, overlapChunkSize);

        log.info("分块完成: {} 个文档 → {} 个分块", docList.size(), chunkedFiles.size());

        // Step3: 输出 chunk JSONL（供入库使用）
        TextUtils.writeJsonl(chunkOutputPath, chunkedFiles);
        log.info("分块文件已输出: {}", chunkOutputPath);

        // Step4: 输出 QA JSONL（供评估使用）
        // 构建 context_id -> List<chunkId> 的映射
        Map<String, List<String>> contextChunksMap = new HashMap<>();
        for (DoFile chunk : chunkedFiles) {
            contextChunksMap
                    .computeIfAbsent(chunk.getFileId(), k -> new ArrayList<>())
                    .add(chunk.getChunkId());
        }

        // 输出 QA 对，附带关联的 chunkId 列表
        List<Map<String, Object>> qaList = new ArrayList<>();
        for (Map<String, Object> record : rawData) {
            String contextId = (String) record.get("context_id");
            if (contextId == null) {
                contextId = (String) record.get("_id");
            }

            Map<String, Object> qa = new LinkedHashMap<>();
            qa.put("question", record.get("input"));
            qa.put("answer", record.get("answers"));
            qa.put("label", record.get("label"));
            qa.put("context_id", contextId);

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("meta");
            if (meta != null) {
                qa.put("title", meta.get("title"));
            } else {
                qa.put("title", record.getOrDefault("dataset", "Unknown"));
            }

            qa.put("chunk_ids", contextChunksMap.getOrDefault(contextId, Collections.emptyList()));
            qa.put("chunk_count", contextChunksMap.getOrDefault(contextId, Collections.emptyList()).size());

            qaList.add(qa);
        }

        TextUtils.writeJsonl(qaOutputPath, qaList);
        log.info("QA 文件已输出: {}", qaOutputPath);

        // 统计信息
        int totalChunks = chunkedFiles.size();
        int totalQA = qaList.size();
        log.info("========== UltraDomain/{} 处理完成 ==========", domain);
        log.info("文档数: {} | 分块数: {} | QA对数: {}", contextFileMap.size(), totalChunks, totalQA);
        log.info("平均每文档分块数: {:.1f}", contextFileMap.isEmpty() ? 0 : (double) totalChunks / contextFileMap.size());

        // 统计分块大小分布
        int[] sizeBins = new int[6]; // 1, 2-5, 6-10, 11-20, 21-50, 50+
        for (Map.Entry<String, List<String>> entry : contextChunksMap.entrySet()) {
            int n = entry.getValue().size();
            if (n == 1) sizeBins[0]++;
            else if (n <= 5) sizeBins[1]++;
            else if (n <= 10) sizeBins[2]++;
            else if (n <= 20) sizeBins[3]++;
            else if (n <= 50) sizeBins[4]++;
            else sizeBins[5]++;
        }
        log.info("分块数分布: 1块={}, 2-5块={}, 6-10块={}, 11-20块={}, 21-50块={}, 50+={}",
                sizeBins[0], sizeBins[1], sizeBins[2], sizeBins[3], sizeBins[4], sizeBins[5]);
    }
}
