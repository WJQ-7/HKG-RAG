package com.bistu.hkgrag.WorkFlow;


import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.model.Entity;
import com.bistu.hkgrag.dao.neo4j.model.Relation;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DBServiceTest {
    private EntityService entityService;
    private FileService fileService;
    private ClusterService clusterService;
    private EmbeddingModel embeddingModel;
    private Neo4jService tripleService;


    public DBServiceTest(@Autowired EntityService entityService,
                         @Autowired FileService fileService,
                         @Autowired ClusterService clusterService,
                         @Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel,
                         @Autowired Neo4jService tripleService) {
        this.entityService = entityService;
        this.fileService = fileService;
        this.clusterService = clusterService;
        this.embeddingModel = embeddingModel;
        this.tripleService = tripleService;
    }


    // ==================== 新 Entity/Relation 模型的 Milvus 插入 ====================
    // 三种实体类型对应三个不同的 Milvus 表：
    //   CONTENT_ENTITY → EntityService → entity 表
    //   DOCUMENT_ENTITY → FileService   → file 表
    //   CLUSTER_ENTITY  → ClusterService → cluster 表

    /**
     * 向 Milvus entity 表插入内容实体（CONTENT_ENTITY）
     * 字段：entityId、entityName、entityDescription、entityInfo（三路 embedding）
     */
    @Test
    @Order(1)
    public void testMilvusInsertContentEntities() {
        String inputPath = "src/main/resources/data/all_entities.jsonl";
        int batchSize = 200;
        int embedBatchSize = 10;

        entityService.createEntityCollection();

        Set<String> existedIds = new HashSet<>();
        List<DoEntity> batchList = new ArrayList<>(batchSize);
        int totalRead = 0, totalInserted = 0;

        try {
            List<com.bistu.hkgrag.dao.neo4j.model.Entity> neoEntities =
                    TextUtils.readJsonl(inputPath, com.bistu.hkgrag.dao.neo4j.model.Entity.class);
            System.out.println("[内容实体] 文件读取实体总数: " + neoEntities.size());

            for (com.bistu.hkgrag.dao.neo4j.model.Entity neoEntity : neoEntities) {
                if (neoEntity.getNodeType() != com.bistu.hkgrag.dao.neo4j.model.Entity.Type.CONTENT_ENTITY) continue;
                totalRead++;
                if (neoEntity.getEntityId() == null || neoEntity.getEntityId().isEmpty()) continue;
                if (!existedIds.add(neoEntity.getEntityId())) continue;

                DoEntity doEntity = new DoEntity();
                doEntity.setEntityId(neoEntity.getEntityId());
                doEntity.setEntityName(safeSubstring(neoEntity.getEntityName(), 1024));
                doEntity.setEntityType(neoEntity.getEntityType());
                doEntity.setEntityDescription(safeSubstring(neoEntity.getEntityDescription(), 8192));
                doEntity.setEntityInfo(safeSubstring(neoEntity.getEntityInfo(), 4096));
                batchList.add(doEntity);

                if (batchList.size() >= batchSize) {
                    totalInserted += embedAndInsertEntities(batchList, embedBatchSize);
                    batchList.clear();
                }
            }
            if (!batchList.isEmpty()) {
                totalInserted += embedAndInsertEntities(batchList, embedBatchSize);
            }

            System.out.println("========== Milvus 内容实体 插入完成 ==========");
            System.out.println("匹配总数: " + totalRead + " | 去重后: " + existedIds.size() + " | 成功插入: " + totalInserted);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Milvus 内容实体 插入失败: " + e.getMessage());
        }
    }

    /**
     * 向 Milvus file 表插入文档实体（DOCUMENT_ENTITY）
     * 映射：entityId→fileId, entityName→fileName, entityDescription→topic, entityInfo→content
     * embedding：topic（entityDescription）和 content（entityInfo）两路
     */
    @Test
    @Order(2)
    public void testMilvusInsertDocumentEntities() {
        String inputPath = "src/main/resources/data/chunk_topic.jsonl";
        int batchSize = 100;
        int embedBatchSize = 10;

        Gson gson = new Gson();

        // ✅ 只创建一次表
        fileService.createFileCollection();

        Set<String> existedChunkIds = new HashSet<>();
        List<DoFile> batchList = new ArrayList<>(batchSize);
        int totalInserted = 0;

        try (BufferedReader reader = Files.newBufferedReader(Path.of(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject json = gson.fromJson(line, JsonObject.class);

                if (json.get("chunkId") == null) continue;
                String chunkId = json.get("chunkId").getAsString();

                // 去重
                if (!existedChunkIds.add(chunkId)) {
                    System.out.println("发现重复 chunkId：" + chunkId);
                    continue;
                }

                DoFile doFile = new DoFile();
                doFile.setFileId(json.get("fileId").getAsString());
                doFile.setChunkId(chunkId);
                doFile.setFileName(json.get("fileName").getAsString());
                doFile.setTopic(json.get("topic").getAsString());
                doFile.setContent(json.get("content").getAsString());
                batchList.add(doFile);

                // 满批处理
                if (batchList.size() >= batchSize) {
                    try {
                        // topic 分批 embedding
                        List<String> topics = batchList.stream()
                                .map(DoFile::getTopic)
                                .collect(Collectors.toList());
                        List<float[]> topicVectors = new ArrayList<>();
                        for (int i = 0; i < topics.size(); i += embedBatchSize) {
                            int end = Math.min(i + embedBatchSize, topics.size());
                            topicVectors.addAll(embeddingModel.embed(topics.subList(i, end)));
                        }
                        // content 分批 embedding
                        List<String> contents = batchList.stream()
                                .map(DoFile::getContent)
                                .collect(Collectors.toList());
                        List<float[]> contentVectors = new ArrayList<>();
                        for (int i = 0; i < contents.size(); i += embedBatchSize) {
                            int end = Math.min(i + embedBatchSize, contents.size());
                            contentVectors.addAll(embeddingModel.embed(contents.subList(i, end)));
                        }
                        if (topicVectors.size() != batchList.size()
                                || contentVectors.size() != batchList.size()) {
                            throw new RuntimeException("embedding数量不一致！");
                        }
                        // 填充向量
                        for (int i = 0; i < batchList.size(); i++) {
                            batchList.get(i).setTopicVector(topicVectors.get(i));
                            batchList.get(i).setContentVector(contentVectors.get(i));
                        }
                        // 插入表
                        boolean success = fileService.insertFiles(batchList);
                        if (success) {
                            totalInserted += batchList.size();
                            System.out.println("批量插入文档实体成功：" + batchList.size());
                        } else {
                            System.out.println("批量插入文档实体失败");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    batchList.clear();
                }
            }

            // 🚨 最后一批
            if (!batchList.isEmpty()) {
                try {
                    List<String> topics = batchList.stream()
                            .map(DoFile::getTopic)
                            .collect(Collectors.toList());
                    List<float[]> topicVectors = new ArrayList<>();
                    for (int i = 0; i < topics.size(); i += embedBatchSize) {
                        int end = Math.min(i + embedBatchSize, topics.size());
                        topicVectors.addAll(embeddingModel.embed(topics.subList(i, end)));
                    }
                    List<String> contents = batchList.stream()
                            .map(DoFile::getContent)
                            .collect(Collectors.toList());
                    List<float[]> contentVectors = new ArrayList<>();
                    for (int i = 0; i < contents.size(); i += embedBatchSize) {
                        int end = Math.min(i + embedBatchSize, contents.size());
                        contentVectors.addAll(embeddingModel.embed(contents.subList(i, end)));
                    }
                    for (int i = 0; i < batchList.size(); i++) {
                        batchList.get(i).setTopicVector(topicVectors.get(i));
                        batchList.get(i).setContentVector(contentVectors.get(i));
                    }
                    boolean success = fileService.insertFiles(batchList);
                    if (success) {
                        totalInserted += batchList.size();
                        System.out.println("最后一批插入文档实体成功：" + batchList.size());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            System.out.println("========== Milvus 文档实体 插入完成 ==========");
            System.out.println("去重后 chunkId 数：" + existedChunkIds.size());
            System.out.println("成功插入文档实体数：" + totalInserted);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Milvus 文档实体 插入失败: " + e.getMessage());
        }
    }

    /**
     * 向 Milvus cluster 表插入聚类实体（CLUSTER_ENTITY）
     * 映射：entityId→clusterId, entityName→clusterName, entityDescription→description
     * embedding：clusterName 和 description 两路
     */
    @Test
    @Order(3)
    public void testMilvusInsertClusterEntities() {
        String inputPath = "src/main/resources/data/all_relations.jsonl";
        int batchSize = 200;
        int embedBatchSize = 10;

        clusterService.createClusterCollection();

        Set<String> existedIds = new HashSet<>();
        List<DoCluster> batchList = new ArrayList<>(batchSize);
        int totalRead = 0, totalInserted = 0;

        try {
            List<com.bistu.hkgrag.dao.neo4j.model.Entity> neoEntities =
                    TextUtils.readJsonl(inputPath, com.bistu.hkgrag.dao.neo4j.model.Entity.class);

            long clusterCount = neoEntities.stream()
                    .filter(e -> e.getNodeType() == com.bistu.hkgrag.dao.neo4j.model.Entity.Type.CLUSTER_ENTITY)
                    .count();
            System.out.println("[聚类实体] 待插入的聚类实体总数: " + clusterCount);

            for (com.bistu.hkgrag.dao.neo4j.model.Entity neoEntity : neoEntities) {
                if (neoEntity.getNodeType() != com.bistu.hkgrag.dao.neo4j.model.Entity.Type.CLUSTER_ENTITY) continue;
                totalRead++;
                if (neoEntity.getEntityId() == null || neoEntity.getEntityId().isEmpty()) continue;
                if (!existedIds.add(neoEntity.getEntityId())) continue;

                DoCluster doCluster = new DoCluster();
                doCluster.setClusterId(neoEntity.getEntityId());            // entityId → clusterId
                doCluster.setClusterName(neoEntity.getEntityName());        // entityName → clusterName
                doCluster.setDescription(neoEntity.getEntityDescription() != null
                        ? neoEntity.getEntityDescription() : "");
                batchList.add(doCluster);

                if (batchList.size() >= batchSize) {
                    totalInserted += embedAndInsertClusters(batchList, embedBatchSize);
                    batchList.clear();
                }
            }
            if (!batchList.isEmpty()) {
                totalInserted += embedAndInsertClusters(batchList, embedBatchSize);
            }

            System.out.println("========== Milvus 聚类实体 插入完成 ==========");
            System.out.println("匹配总数: " + totalRead + " | 去重后: " + existedIds.size() + " | 成功插入: " + totalInserted);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Milvus 聚类实体 插入失败: " + e.getMessage());
        }
    }

    // ==================== 各表专用的 embedding + 插入辅助方法 ====================

    /**
     * 对一批 DoEntity 做 entityName + entityDescription 两路 embedding，插入 entity 表
     * （entityInfo 仅存文本不做向量，Milvus 限制最多 4 个向量字段）
     */
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
        System.out.println(success ? "成功插入: " + size : "插入失败，批次大小: " + size);
        return success ? size : 0;
    }


    /**
     * 对一批 DoCluster 做 clusterName + description 两路 embedding，插入 cluster 表
     */
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
        System.out.println(success ? "成功插入: " + size : "插入失败，批次大小: " + size);
        return success ? size : 0;
    }

    /** 按 Milvus schema maxLength 截断字段，防止插入报错 */
    private String safeSubstring(String value, int maxLen) {
        if (value == null) return "";
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    /**
     * 分批调用 embedding 模型
     */
    private List<float[]> batchEmbed(List<String> texts, int embedBatchSize) {
        List<float[]> allVectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += embedBatchSize) {
            int end = Math.min(i + embedBatchSize, texts.size());
            List<String> subList = texts.subList(i, end);
            allVectors.addAll(embeddingModel.embed(subList));
        }
        return allVectors;
    }

    // ==================== Neo4j 新 Entity/Relation 模型的插入测试 ====================

    /**
     * 基于新 Entity 模型插入节点（CONTENT_ENTITY + DOCUMENT_ENTITY）
     */
    @Test
    @Order(4)
    public void test_insertEntities() {
        String inputPath = "src/main/resources/data/all_entities.jsonl";

        List<Entity> entities = TextUtils.readJsonl(inputPath, Entity.class);
        System.out.println("待插入实体总数: " + entities.size());

        int successCount = tripleService.insertEntities(entities);
        System.out.println("========== 实体插入结果 ==========");
        System.out.println("待插入总数: " + entities.size());
        System.out.println("成功数量: " + successCount);
        System.out.println("失败数量: " + (entities.size() - successCount));
    }

    /**
     * 基于新 Relation 模型创建关系
     */
    @Test
    @Order(5)
    public void test_createRelations() {
        String inputPath = "src/main/resources/data/all_relations.jsonl";

        List<Relation> relations = TextUtils.readJsonl(inputPath, Relation.class);
        System.out.println("待插入关系总数: " + relations.size());

        int successCount = tripleService.createRelations(relations);
        System.out.println("========== 关系插入结果 ==========");
        System.out.println("待插入总数: " + relations.size());
        System.out.println("成功数量: " + successCount);
        System.out.println("失败数量: " + (relations.size() - successCount));
    }

}
