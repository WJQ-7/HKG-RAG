package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.neo4j.model.Entity;
import com.bistu.hkgrag.dao.neo4j.model.Relation;
import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.clustering.DBSCANClusterer;
import org.apache.commons.math3.ml.distance.DistanceMeasure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;

@Service
public class GenClusterService {
    private static Logger log = LoggerFactory.getLogger(GenClusterService.class);
    private EmbeddingModel embeddingModel;
    private ChatClient chatClient;
    //余弦距离阈值
    private static final double THRESHOLD = 0.25;

    public GenClusterService(@Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel,
                             @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient) {
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
    }

    @Schema(description = "话题抽取结果")
    public record TopicResult(

            @JsonProperty(value = "clusterName", required = true)
            @Schema(description = "簇名")
            String clusterName,

            @JsonProperty(value = "description", required = true)
            @Schema(description = "簇描述/说明")
            String description

    ) {
    }

    /**
     * 将float[]转为double[]才能是否Apache数学库进行聚类
     */
    static class Point implements Clusterable {
        double[] point;

        public Point(float[] vec) {
            point = new double[vec.length];
            for (int i = 0; i < vec.length; i++) point[i] = vec[i];
        }

        @Override
        public double[] getPoint() {
            return point;
        }
    }

    static class CosineDistance implements DistanceMeasure {
        @Override
        public double compute(double[] a, double[] b) {
            double dot = 0, ma = 0, mb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                ma += a[i] * a[i];
                mb += b[i] * b[i];
            }
            return 1 - dot / (Math.sqrt(ma) * Math.sqrt(mb));
        }
    }

    /**
     * 对指定路径下的文件进行主题聚类，使用新的 Entity/Relation 模型替代旧的 Triple 模型。
     * 生成聚类实体节点文件以及聚类节点与文档节点之间的关系文件。
     *
     * @param inputPath  待聚类文件路径（chunk_topic.jsonl）
     * @param outputPath 输出路径
     */
    public void topicClusterV2(String inputPath, String outputPath) {
        try {
            List<DoFile> fileList = TextUtils.readJsonlToList(inputPath, DoFile.class);
            log.info("待聚类的文件总个数为：{}", fileList.size());

            /**
             * 1. 提取话题
             */
            List<Map<String, String>> topics = new ArrayList<>();
            for (DoFile file : fileList) {
                if (file.getTopic() != null && !file.getTopic().isEmpty()) {
                    Map<String, String> map = new HashMap<>();
                    map.put("fileId", file.getFileId());
                    map.put("fileName", file.getFileName());
                    map.put("topic", file.getTopic());
                    map.put("content", file.getContent());
                    topics.add(map);
                }
            }
            log.info("有效待聚类的话题总个数为：{}", topics.size());

            /**
             * 2. 话题向量化
             */
            List<String> topicList = new ArrayList<>();
            for (Map<String, String> item : topics) {
                topicList.add(item.get("topic"));
            }

            int batchSize = 10;
            ExecutorService executor_embedding = Executors.newFixedThreadPool(5);

            class BatchResult {
                int index;
                List<float[]> vectors;

                BatchResult(int index, List<float[]> vectors) {
                    this.index = index;
                    this.vectors = vectors;
                }
            }

            CompletionService<BatchResult> completionService = new ExecutorCompletionService<>(executor_embedding);

            int batchIndex = 0;
            for (int i = 0; i < topicList.size(); i += batchSize) {
                int start = i;
                int currentIndex = batchIndex++;
                completionService.submit(() -> {
                    int end = Math.min(start + batchSize, topicList.size());
                    List<String> batch = topicList.subList(start, end);
                    List<float[]> vecs = embeddingModel.embed(batch);
                    return new BatchResult(currentIndex, vecs);
                });
            }

            Map<Integer, List<float[]>> resultMap = new HashMap<>();
            for (int i = 0; i < batchIndex; i++) {
                BatchResult result = completionService.take().get();
                resultMap.put(result.index, result.vectors);
            }

            List<float[]> embeddings = new ArrayList<>();
            for (int i = 0; i < batchIndex; i++) {
                embeddings.addAll(resultMap.get(i));
            }

            executor_embedding.shutdown();

            /**
             * 3. DBSCAN 聚类（余弦相似度）
             */
            List<Point> points = new ArrayList<>();
            for (float[] embedding : embeddings) {
                points.add(new Point(embedding));
            }

            DBSCANClusterer<Point> clusterer = new DBSCANClusterer<>(THRESHOLD, 0, new CosineDistance());
            List<Cluster<Point>> clusters = clusterer.cluster(points);
            log.info("聚类后的簇数为：[{}]", clusters.size());

            /**
             * 4. 生成每个簇的 Entity（聚类节点）和 Relation（聚类→文档关系）
             */
            Semaphore semaphore = new Semaphore(3);
            ExecutorService executor = Executors.newFixedThreadPool(3);

            Queue<Entity> clusterEntitiesQueue = new ConcurrentLinkedQueue<>();
            Queue<Relation> clusterRelationsQueue = new ConcurrentLinkedQueue<>();

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Cluster<Point> cluster : clusters) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    if (cluster.getPoints().isEmpty()) return;

                    // 先构建 clusterId、fileIdList 和 prompt（与重试无关的数据）
                    String clusterId = UUID.randomUUID().toString();
                    Set<String> fileIdSet = new LinkedHashSet<>(); // 去重，同一文档的多个chunk只保留一个关系

                    final int MAX_PROMPT_DOC_CHARS = 25000;
                    final int MAX_SAMPLE_DOCS = 30;

                    List<String> docInfos = new ArrayList<>();
                    for (Point point : cluster.getPoints()) {
                        int pointIndex = points.indexOf(point);
                        String fileId = topics.get(pointIndex).get("fileId");
                        String fileName = topics.get(pointIndex).get("fileName");
                        String topic = topics.get(pointIndex).get("topic");
                        String content = topics.get(pointIndex).get("content");
                        fileIdSet.add(fileId);
                        docInfos.add(String.format("主题：%s 文档名：%s 文档内容：%s",
                                topic, fileName, content));
                    }

                    StringBuilder contentBuilder = new StringBuilder();
                    boolean needSample = false;
                    for (String info : docInfos) {
                        if (contentBuilder.length() + info.length() > MAX_PROMPT_DOC_CHARS) {
                            needSample = true;
                            break;
                        }
                        contentBuilder.append(info).append("\n");
                    }

                    if (needSample) {
                        log.info("簇包含 {} 个文档，prompt 过长，采样 {} 个后生成描述",
                                docInfos.size(), MAX_SAMPLE_DOCS);
                        contentBuilder = new StringBuilder();
                        int step = Math.max(1, docInfos.size() / MAX_SAMPLE_DOCS);
                        for (int i = 0; i < docInfos.size(); i += step) {
                            contentBuilder.append(docInfos.get(i)).append("\n");
                            if (contentBuilder.length() > MAX_PROMPT_DOC_CHARS) break;
                        }
                    }

                    String prompt = String.format(PromptTemplates.Topic_Description_Extraction, contentBuilder);

                    // 重试只在 LLM 调用上，entity/relation 创建只做一次
                    TopicResult topicResult = null;
                    int maxRetry = 3;
                    for (int attempt = 1; attempt <= maxRetry; attempt++) {
                        try {
                            semaphore.acquire();
                            try {
                                topicResult = chatClient.prompt().user(prompt).call().entity(TopicResult.class);
                            } finally {
                                semaphore.release();
                            }
                            break;
                        } catch (Exception e) {
                            if (attempt == maxRetry) {
                                log.error("簇 LLM 调用失败，已重试 {} 次", maxRetry, e);
                                return; // 彻底失败，跳过这个簇
                            }
                            try {
                                Thread.sleep(1000L * attempt);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }

                    if (topicResult == null) return;

                    // 只创建一次 entity 和 relations
                    Entity clusterEntity = new Entity();
                    clusterEntity.setEntityId(clusterId);
                    clusterEntity.setNodeType(Entity.Type.CLUSTER_ENTITY);
                    clusterEntity.setEntityName(topicResult.clusterName());
                    clusterEntity.setEntityType("主题聚类节点");
                    clusterEntity.setEntityDescription(topicResult.description());
                    clusterEntity.setEntityInfo("");
                    clusterEntity.setFileId("");
                    clusterEntity.setChunkId("");
                    clusterEntitiesQueue.add(clusterEntity);

                    for (String fileId : fileIdSet) {
                        Relation rel = new Relation();
                        rel.setSourceId(clusterId);
                        rel.setTargetId(fileId);
                        rel.setKeywords("cluster+" + topicResult.clusterName());
                        rel.setRelationDescription("包含");
                        clusterRelationsQueue.add(rel);
                    }
                }, executor);
                futures.add(future);
            }

            // 等待所有簇完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 写入输出文件
            TextUtils.writeJsonlAppend(outputPath + "/all_entities_test1.jsonl", new ArrayList<>(clusterEntitiesQueue));
            TextUtils.writeJsonlAppend(outputPath + "/all_relations_test1.jsonl", new ArrayList<>(clusterRelationsQueue));

            executor.shutdown();
            log.info("主题聚类 V2 成功（使用新 Entity/Relation 模型），生成聚类节点: {}, 聚类关系: {}",
                    clusterEntitiesQueue.size(), clusterRelationsQueue.size());
        } catch (Exception e) {
            e.printStackTrace();
            log.error("主题聚类 V2 失败", e);
        }
    }
}
