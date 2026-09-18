package com.bistu.hkgrag.WorkFlow;

import com.bistu.hkgrag.Datasets.UltraDomainDataset;
import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.model.Entity;
import com.bistu.hkgrag.dao.neo4j.model.Relation;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.bistu.hkgrag.llm.agent.TestAgent;
import com.bistu.hkgrag.llm.service.ExtractionService;
import com.bistu.hkgrag.llm.service.GenClusterService;
import com.bistu.hkgrag.llm.agent.MultiAgent;
import com.bistu.hkgrag.llm.evaluation.Evaluator;
import com.bistu.hkgrag.llm.evaluation.Evaluator.EvalSummary;
import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.service.BaseLineService;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * UltraDomain 数据集完整流水线（支持分阶段执行 + 一键全流程）
 * <p>
 * <b>三阶段拆分（可独立运行，互不干扰）：</b>
 * <ul>
 *   <li><b>阶段1 数据抽取</b>: Step1分块 + Step2 Topic提取 + Step3 实体关系抽取 + Step4 聚类</li>
 *   <li><b>阶段2 建立数据库</b>: Step5 Milvus入库(entity/file/cluster) + Step6 Neo4j入库(实体节点+关系)</li>
 *   <li><b>阶段3 评估</b>: Step7 NaiveRAG + MultiAgent 评估，生成指标对比</li>
 * </ul>
 * <p>
 * <b>一键完整流水线</b>: fullPipeline() = stage1 + stage2 + stage3，保留完整流程
 *
 * <pre>
 * 使用方式:
 *   阶段1: stage1_Extraction("agriculture")
 *   阶段2: stage2_DatabaseBuild("agriculture")
 *   阶段3: stage3_Evaluation("agriculture")
 *   一键:  fullPipeline("agriculture")
 * </pre>
 *
 * @author wangjingqi
 */
@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class UltraDomainPipeline {
    private static final Logger log = LoggerFactory.getLogger(UltraDomainPipeline.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 注入所有需要的 Service ====================

    // UltraDomainDataset 是 @SpringBootTest 测试类，不能直接 Autowired，改为手动创建
    private final ExtractionService extractionService;
    private final GenClusterService genClusterService;

    // DBServiceTest 中用到的 Service
    private final EntityService entityService;
    private final FileService fileService;
    private final ClusterService clusterService;
    private final Neo4jService neo4jService;
    private final EmbeddingModel embeddingModel;

    // 评估用到的 Service
    private final MultiAgent multiAgent;
    private final BaseLineService baseLineService;
    private final TestAgent testAgent;

    // 分块配置（用于创建 UltraDomainDataset 实例）
    @Value("${HKG-RAG.chunk.max-size}")
    private int maxChunkSize;

    @Value("${HKG-RAG.chunk.overlap-chunk-size}")
    private int overlapChunkSize;

    private static final String BASE_DIR = "src/main/resources/data/datasets/UltraDomain/";
    private static final String RESULT_DIR = "src/main/resources/data/datasets/UltraDomain/";

    public UltraDomainPipeline(@Autowired ExtractionService extractionService,
                               @Autowired GenClusterService genClusterService,
                               @Autowired EntityService entityService,
                               @Autowired FileService fileService,
                               @Autowired ClusterService clusterService,
                               @Autowired Neo4jService neo4jService,
                               @Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel,
                               @Autowired MultiAgent multiAgent,
                               @Autowired BaseLineService baseLineService,
                               @Autowired TestAgent testAgent) {
        this.extractionService = extractionService;
        this.genClusterService = genClusterService;
        this.entityService = entityService;
        this.fileService = fileService;
        this.clusterService = clusterService;
        this.neo4jService = neo4jService;
        this.embeddingModel = embeddingModel;
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
        this.testAgent = testAgent;
    }

    // ==================== 阶段1: 数据抽取（分块 + Topic + 实体关系 + 聚类） ====================

    @Test
    @Order(1)
    public void stage1_Agriculture() throws Exception {
        stage1_Extraction("agriculture");
    }

    @Test
    @Order(2)
    public void stage1_CS() throws Exception {
        stage1_Extraction("cs");
    }

    @Test
    @Order(3)
    public void stage1_Legal() throws Exception {
        stage1_Extraction("legal");
    }

    @Test
    @Order(4)
    public void stage1_Mix() throws Exception {
        stage1_Extraction("mix");
    }

    /**
     * 阶段1: 数据抽取
     * 包含: Step1分块 + Step2 Topic提取 + Step3 实体关系抽取 + Step4 聚类
     * 产出: chunk.jsonl, chunk_topic.jsonl, all_entities.jsonl, all_relations.jsonl
     */
    public void stage1_Extraction(String domain) throws Exception {
        String domainDir = BASE_DIR + domain + "/";

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段1: 数据抽取                           ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Step1: 分块
        log.info("===== [{}] Step1: 分块 =====", domain);
        UltraDomainDataset ultraDomainDataset = new UltraDomainDataset();
        ultraDomainDataset.setMaxChunkSize(maxChunkSize);
        ultraDomainDataset.setOverlapChunkSize(overlapChunkSize);
        ultraDomainDataset.extractDomain(domain);

        String chunkPath = domainDir + "chunk.jsonl";
        String chunkTopicPath = domainDir + "chunk_topic.jsonl";

        // Step2: Topic提取
        log.info("===== [{}] Step2: Topic提取 =====", domain);
        extractionService.fileTopicExtraction(chunkPath, chunkTopicPath);

        // Step3: 实体关系抽取
        log.info("===== [{}] Step3: 实体关系抽取 =====", domain);
        extractionService.entitiesAndRelationExtractionMultiThread(chunkPath, domainDir);

        // Step4: 聚类
        log.info("===== [{}] Step4: 聚类 =====", domain);
        genClusterService.topicClusterV2(chunkTopicPath, domainDir);

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段1: 数据抽取 完成！                    ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== 阶段2: 建库（Milvus + Neo4j） ====================

    @Test
    @Order(5)
    public void stage2_Agriculture() throws Exception {
        stage2_DatabaseBuild("agriculture");
    }

    @Test
    @Order(6)
    public void stage2_CS() throws Exception {
        stage2_DatabaseBuild("cs");
    }

    @Test
    @Order(7)
    public void stage2_Legal() throws Exception {
        stage2_DatabaseBuild("legal");
    }

    @Test
    @Order(8)
    public void stage2_Mix() throws Exception {
        stage2_DatabaseBuild("mix");
    }

    /**
     * 阶段2: 建立数据库
     * 包含: Step5 Milvus入库 + Step6 Neo4j入库
     * 依赖: 阶段1产出的 all_entities.jsonl, all_relations.jsonl, chunk_topic.jsonl
     */
    public void stage2_DatabaseBuild(String domain) throws Exception {
        String domainDir = BASE_DIR + domain + "/";
        String entitiesPath = domainDir + "all_entities.jsonl";
        String relationsPath = domainDir + "all_relations.jsonl";
        String chunkTopicPath = domainDir + "chunk_topic.jsonl";

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段2: 建立数据库                         ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Step5: Milvus入库
        log.info("===== [{}] Step5: Milvus入库 =====", domain);

        // 5a: entity 表（CONTENT_ENTITY）
        insertContentEntitiesToMilvus(entitiesPath);
        // 5b: file 表（DOCUMENT_ENTITY）
        insertDocumentEntitiesToMilvus(chunkTopicPath);
        // 5c: cluster 表（CLUSTER_ENTITY）
        insertClusterEntitiesToMilvus(entitiesPath);

        // Step6: Neo4j入库
        log.info("===== [{}] Step6: Neo4j入库 =====", domain);

        // 6a: 插入实体节点
        List<Entity> entities = TextUtils.readJsonl(entitiesPath, Entity.class);
        int entityInserted = neo4jService.insertEntities(entities);
        log.info("[{}] Neo4j 实体插入: 总数={}, 成功={}", domain, entities.size(), entityInserted);

        // 6b: 创建关系
        List<Relation> relations = TextUtils.readJsonl(relationsPath, Relation.class);
        int relInserted = neo4jService.createRelations(relations);
        log.info("[{}] Neo4j 关系插入: 总数={}, 成功={}", domain, relations.size(), relInserted);

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段2: 建立数据库 完成！                  ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== 阶段3: 评估 ====================

    @Test
    @Order(9)
    public void stage3_Agriculture() throws Exception {
        stage3_Evaluation("agriculture");
    }

    @Test
    @Order(10)
    public void stage3_CS() throws Exception {
        stage3_Evaluation("cs");
    }

    @Test
    @Order(11)
    public void stage3_Legal() throws Exception {
        stage3_Evaluation("legal");
    }

    @Test
    @Order(12)
    public void stage3_Mix() throws Exception {
        stage3_Evaluation("mix");
    }

    /**
     * 阶段3: 评估
     * 包含: Step7 NaiveRAG + HKG-RAG 评估
     * 依赖: 阶段2的数据库已建好
     */
    public void stage3_Evaluation(String domain) throws Exception {
        String domainDir = BASE_DIR + domain + "/";
        String resultDomainDir = RESULT_DIR + domain + "/";
        String qaInputPath = domainDir + "qa.jsonl";

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段3: 评估 (NaiveRAG + HKG-RAG)       ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // 确保结果目录存在
        new java.io.File(resultDomainDir).mkdirs();

        Map<String, Function<String, String>> methods = new LinkedHashMap<>();
        methods.put("NaiveRAG", q -> baseLineService.naiveRAG(q).getAnswer());
        methods.put("HKGRAG", multiAgent::testMultiAgent);
        methods.put("HKGRAG-without-MultiAgent", testAgent::test_Agent);

        Map<String, Map<String, Double>> allScores = new LinkedHashMap<>();

        List<Map<String, Object>> qaData = readQAData(qaInputPath);
        log.info("[{}] 评估数据 {} 条", domain, qaData.size());

        for (Map.Entry<String, Function<String, String>> entry : methods.entrySet()) {
            String methodName = entry.getKey();
            Function<String, String> ragMethod = entry.getValue();

            log.info("---------- [{}|{}] 开始 ----------", domain, methodName);

            List<EvalRecord> records = new ArrayList<>();
            for (int i = 0; i < qaData.size(); i++) {
                Map<String, Object> qa = qaData.get(i);
                String question = (String) qa.get("question");

                Object answerObj = qa.get("answer");
                String goldAnswer;
                if (answerObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> answerList = (List<String>) answerObj;
                    goldAnswer = String.join("; ", answerList);
                } else {
                    goldAnswer = answerObj != null ? answerObj.toString() : "";
                }

                String predAnswer;
                try {
                    predAnswer = ragMethod.apply(question);
                } catch (Exception e) {
                    log.error("[{}|{}] 第 {} 条生成失败: {}", domain, methodName, i + 1, e.getMessage());
                    predAnswer = "[ERROR] " + e.getMessage();
                }

                EvalRecord record = new EvalRecord();
                record.setFileId((String) qa.getOrDefault("context_id", ""));
                record.setType(TestSet.Type.SINGLE_HOP);
                record.setQuestion(question);
                record.setPredAnswer(predAnswer != null ? predAnswer : "");
                record.setGoldAnswer(goldAnswer);
                records.add(record);

                log.info("[{}|{}] 进度: {}/{}", domain, methodName, i + 1, qaData.size());
            }

            // 写 QA 文件
            String qaPath = resultDomainDir + methodName + "_QA.jsonl";
            TextUtils.writeJsonl(qaPath, records);

            // 词级评估
            String detailPath = resultDomainDir + methodName + "_Detail.jsonl";
            String summaryPath = resultDomainDir + methodName + "_Summary.txt";
            EvalSummary summary = Evaluator.evaluate(records, detailPath, summaryPath);
            if (summary != null) {
                allScores.put(methodName, summary.overall());
                log.info("[{}|{}] 评估完成: EM={:.4f}, F1={:.4f}, RougeL={:.4f}, Recall={:.4f}",
                        domain, methodName,
                        summary.overall().get("EM"),
                        summary.overall().get("F1"),
                        summary.overall().get("RougeL"),
                        summary.overall().get("Recall"));
            }
        }

        // 写对比表格
        if (!allScores.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("==================== [").append(domain).append("] NaiveRAG vs HKG-RAG 对比 ====================\n\n");
            sb.append(String.format("%-15s %10s %10s %10s %10s\n", "Method", "EM", "F1", "RougeL", "Recall"));
            sb.append("----------------------------------------------------------------------\n");
            for (Map.Entry<String, Map<String, Double>> e : allScores.entrySet()) {
                Map<String, Double> s = e.getValue();
                sb.append(String.format("%-15s %10.4f %10.4f %10.4f %10.4f\n",
                        e.getKey(), s.get("EM"), s.get("F1"), s.get("RougeL"), s.get("Recall")));
            }
//            java.nio.file.Files.writeString(Path.of(resultDomainDir + domain + "_Comparison-v1.txt"), sb.toString());
        }

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 阶段3: 评估 完成！                        ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== 一键完整流水线（保留） ====================

    @Test
    @Order(13)
    public void fullPipeline_Agriculture() throws Exception {
        fullPipeline("agriculture");
    }

    @Test
    @Order(14)
    public void fullPipeline_CS() throws Exception {
        fullPipeline("cs");
    }

    @Test
    @Order(15)
    public void fullPipeline_Legal() throws Exception {
        fullPipeline("legal");
    }

    @Test
    @Order(16)
    public void fullPipeline_Mix() throws Exception {
        fullPipeline("mix");
    }

    /**
     * 一键完整流水线: 依次执行 阶段1 → 阶段2 → 阶段3
     * 等同于分别调用 stage1_Extraction + stage2_DatabaseBuild + stage3_Evaluation
     */
    public void fullPipeline(String domain) throws Exception {
        stage1_Extraction(domain);
        stage2_DatabaseBuild(domain);
        stage3_Evaluation(domain);

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  UltraDomain [{}] 完整流水线全部完成！                      ║", domain);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    // ==================== Milvus 入库辅助方法 ====================

    /**
     * 向 Milvus entity 表插入内容实体（CONTENT_ENTITY）
     */
    private void insertContentEntitiesToMilvus(String entitiesPath) {
        int batchSize = 200;
        int embedBatchSize = 20;

        entityService.createEntityCollection();

        Set<String> existedIds = new HashSet<>();
        List<DoEntity> batchList = new ArrayList<>(batchSize);
        int totalInserted = 0;

        try {
            List<Entity> neoEntities = TextUtils.readJsonl(entitiesPath, Entity.class);
            log.info("[Milvus Entity] 文件读取实体总数: {}", neoEntities.size());

            for (Entity neoEntity : neoEntities) {
                if (neoEntity.getNodeType() != Entity.Type.CONTENT_ENTITY) continue;
                if (neoEntity.getEntityId() == null || neoEntity.getEntityId().isEmpty()) continue;
                if (!existedIds.add(neoEntity.getEntityId())) continue;

                DoEntity doEntity = new DoEntity();
                doEntity.setEntityId(neoEntity.getEntityId());
                doEntity.setEntityName(safeSubstring(neoEntity.getEntityName(), 1000));
                doEntity.setEntityType(neoEntity.getEntityType());
                doEntity.setEntityDescription(safeSubstring(neoEntity.getEntityDescription(), 8100));
                doEntity.setEntityInfo(safeSubstring(neoEntity.getEntityInfo(), 4000));
                batchList.add(doEntity);

                if (batchList.size() >= batchSize) {
                    totalInserted += embedAndInsertEntities(batchList, embedBatchSize);
                    batchList.clear();
                }
            }
            if (!batchList.isEmpty()) {
                totalInserted += embedAndInsertEntities(batchList, embedBatchSize);
            }

            log.info("[Milvus Entity] 插入完成: 去重后={}, 成功={}", existedIds.size(), totalInserted);
        } catch (Exception e) {
            log.error("[Milvus Entity] 插入失败", e);
        }
    }

    /**
     * 向 Milvus file 表插入文档实体（DOCUMENT_ENTITY）
     */
    private void insertDocumentEntitiesToMilvus(String chunkTopicPath) {
        int batchSize = 100;
        int embedBatchSize = 10;

        fileService.createFileCollection();

        Gson gson = new Gson();
        Set<String> existedChunkIds = new HashSet<>();
        List<DoFile> batchList = new ArrayList<>(batchSize);
        int totalInserted = 0;

        try (BufferedReader reader = Files.newBufferedReader(Path.of(chunkTopicPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject json = gson.fromJson(line, JsonObject.class);
                if (json.get("chunkId") == null) continue;
                String chunkId = json.get("chunkId").getAsString();
                if (!existedChunkIds.add(chunkId)) continue;

                DoFile doFile = new DoFile();
                doFile.setFileId(json.get("fileId").getAsString());
                doFile.setChunkId(chunkId);
                doFile.setFileName(safeSubstring(json.get("fileName").getAsString(), 512));
                doFile.setTopic(safeSubstring(json.has("topic") ? json.get("topic").getAsString() : "", 256));
                doFile.setContent(safeSubstring(json.get("content").getAsString(), 28000));
                batchList.add(doFile);

                if (batchList.size() >= batchSize) {
                    totalInserted += embedAndInsertFiles(batchList, embedBatchSize);
                    batchList.clear();
                }
            }
            if (!batchList.isEmpty()) {
                totalInserted += embedAndInsertFiles(batchList, embedBatchSize);
            }

            log.info("[Milvus File] 插入完成: 去重后={}, 成功={}", existedChunkIds.size(), totalInserted);
        } catch (Exception e) {
            log.error("[Milvus File] 插入失败", e);
        }
    }

    /**
     * 向 Milvus cluster 表插入聚类实体（CLUSTER_ENTITY）
     */
    private void insertClusterEntitiesToMilvus(String entitiesPath) {
        int batchSize = 200;
        int embedBatchSize = 10;

        clusterService.createClusterCollection();

        Set<String> existedIds = new HashSet<>();
        List<DoCluster> batchList = new ArrayList<>(batchSize);
        int totalInserted = 0;

        try {
            List<Entity> neoEntities = TextUtils.readJsonl(entitiesPath, Entity.class);

            long clusterCount = neoEntities.stream()
                    .filter(e -> e.getNodeType() == Entity.Type.CLUSTER_ENTITY)
                    .count();
            log.info("[Milvus Cluster] 待插入聚类实体总数: {}", clusterCount);

            for (Entity neoEntity : neoEntities) {
                if (neoEntity.getNodeType() != Entity.Type.CLUSTER_ENTITY) continue;
                if (neoEntity.getEntityId() == null || neoEntity.getEntityId().isEmpty()) continue;
                if (!existedIds.add(neoEntity.getEntityId())) continue;

                DoCluster doCluster = new DoCluster();
                doCluster.setClusterId(neoEntity.getEntityId());
                doCluster.setClusterName(safeSubstring(neoEntity.getEntityName(), 512));
                doCluster.setDescription(safeSubstring(neoEntity.getEntityDescription() != null
                        ? neoEntity.getEntityDescription() : "", 1024));
                batchList.add(doCluster);

                if (batchList.size() >= batchSize) {
                    totalInserted += embedAndInsertClusters(batchList, embedBatchSize);
                    batchList.clear();
                }
            }
            if (!batchList.isEmpty()) {
                totalInserted += embedAndInsertClusters(batchList, embedBatchSize);
            }

            log.info("[Milvus Cluster] 插入完成: 去重后={}, 成功={}", existedIds.size(), totalInserted);
        } catch (Exception e) {
            log.error("[Milvus Cluster] 插入失败", e);
        }
    }

    // ==================== Embedding + 插入辅助方法 ====================

    private int embedAndInsertEntities(List<DoEntity> batch, int embedBatchSize) {
        int size = batch.size();
        List<String> names = batch.stream().map(DoEntity::getEntityName).collect(Collectors.toList());
        List<String> descriptions = batch.stream()
                .map(e -> e.getEntityDescription() != null ? e.getEntityDescription() : "")
                .collect(Collectors.toList());

        List<float[]> nameVectors = batchEmbed(names, embedBatchSize);
        List<float[]> descVectors = batchEmbed(descriptions, embedBatchSize);

        for (int i = 0; i < size; i++) {
            batch.get(i).setEntityNameVector(nameVectors.get(i));
            batch.get(i).setEntityDescriptionVector(descVectors.get(i));
        }

        boolean success = entityService.insertEntities(batch);
        if (!success) log.warn("[Milvus Entity] 插入失败，批次大小: {}", size);
        return success ? size : 0;
    }

    private int embedAndInsertFiles(List<DoFile> batch, int embedBatchSize) {
        int size = batch.size();
        List<String> topics = batch.stream().map(DoFile::getTopic).collect(Collectors.toList());
        List<String> contents = batch.stream().map(DoFile::getContent).collect(Collectors.toList());

        List<float[]> topicVectors = batchEmbed(topics, embedBatchSize);
        List<float[]> contentVectors = batchEmbed(contents, embedBatchSize);

        for (int i = 0; i < size; i++) {
            batch.get(i).setTopicVector(topicVectors.get(i));
            batch.get(i).setContentVector(contentVectors.get(i));
        }

        boolean success = fileService.insertFiles(batch);
        if (!success) log.warn("[Milvus File] 插入失败，批次大小: {}", size);
        return success ? size : 0;
    }

    private int embedAndInsertClusters(List<DoCluster> batch, int embedBatchSize) {
        int size = batch.size();
        List<String> names = batch.stream()
                .map(c -> c.getClusterName() != null ? c.getClusterName() : "")
                .collect(Collectors.toList());
        List<String> descriptions = batch.stream()
                .map(c -> c.getDescription() != null ? c.getDescription() : "")
                .collect(Collectors.toList());

        List<float[]> nameVectors = batchEmbed(names, embedBatchSize);
        List<float[]> descVectors = batchEmbed(descriptions, embedBatchSize);

        for (int i = 0; i < size; i++) {
            batch.get(i).setClusterNameVector(nameVectors.get(i));
            batch.get(i).setDescriptionVector(descVectors.get(i));
        }

        boolean success = clusterService.insertClusters(batch);
        if (!success) log.warn("[Milvus Cluster] 插入失败，批次大小: {}", size);
        return success ? size : 0;
    }

    // qwen embedding 模型上下文限制 40960 token，中文约 1.3 chars/token，50000 字符留够余量
    private static final int EMBED_MAX_CHARS = 50000;

    /** 按 Milvus schema maxLength 截断字段，在嵌入前防止插入报错 */
    private String safeSubstring(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private String truncateForEmbed(String text) {
        if (text == null) return "";
        return text.length() > EMBED_MAX_CHARS ? text.substring(0, EMBED_MAX_CHARS) : text;
    }

    private List<float[]> batchEmbed(List<String> texts, int embedBatchSize) {
        List<float[]> allVectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += embedBatchSize) {
            int end = Math.min(i + embedBatchSize, texts.size());
            List<String> batch = texts.subList(i, end).stream()
                    .map(this::truncateForEmbed)
                    .collect(Collectors.toList());
            allVectors.addAll(embeddingModel.embed(batch));
        }
        return allVectors;
    }

    // ==================== QA 数据读取 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readQAData(String path) throws Exception {
        List<Map<String, Object>> data = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new FileReader(path, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> record = objectMapper.readValue(line, Map.class);
                data.add(record);
            }
        }
        return data;
    }
}
