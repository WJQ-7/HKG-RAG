package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.neo4j.model.Triple;
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
     * 对指定路径下的文件进行主题聚类 并生成聚类节点文件以及聚类节点与文档节点的关系文件
     *
     * @param inputPath  待聚类文件路径
     * @param outputPath 输出路径
     */
    public void topicCluster(String inputPath, String outputPath) {
        try {
            List<DoFile> fileList = TextUtils.readJsonlToList(inputPath, DoFile.class);
            log.info("待聚类的文件总个数为：{}", fileList.size());
            /**
             * 1.提取话题
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
             * 2.话题向量化
             */
            List<String> topicList = new ArrayList<>();
            for (Map<String, String> item : topics) {
                topicList.add(item.get("topic"));
            }

            //List<float[]> embeddings = embeddingModel.embed(topicList);
            //话题向量化需要批量处理
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

            // 1. 提交任务（带 index）
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

            // 2. 收集结果（先存 Map）
            Map<Integer, List<float[]>> resultMap = new HashMap<>();

            for (int i = 0; i < batchIndex; i++) {
                BatchResult result = completionService.take().get();
                resultMap.put(result.index, result.vectors);
            }

            // 3. 按顺序还原 embeddings
            List<float[]> embeddings = new ArrayList<>();

            for (int i = 0; i < batchIndex; i++) {
                embeddings.addAll(resultMap.get(i));
            }

            executor_embedding.shutdown();
            /**
             * 3.层次聚类（余弦相似度）
             */
            //DBSCAN 聚类
            List<Point> points = new ArrayList<>();
            for (float[] embedding : embeddings) {
                points.add(new Point(embedding));
            }

            //构建簇对象（距离阈值，一个簇中最少的点数，距离计算方式）
            DBSCANClusterer<Point> clusterer = new DBSCANClusterer<Point>(THRESHOLD, 0, new CosineDistance());
            List<Cluster<Point>> clusters = clusterer.cluster(points);
            log.info("聚类后的簇数为：[{}]", clusters.size());

            /**
             * 4.生成每个簇的Triple
             */

            Semaphore semaphore = new Semaphore(3); // 限流，控制同时调用模型的簇数
            ExecutorService executor = Executors.newFixedThreadPool(3);

            Queue<Triple> topicTriplesQueue = new ConcurrentLinkedQueue<>();
            Queue<Triple> relationTriplesQueue = new ConcurrentLinkedQueue<>();

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Cluster<Point> cluster : clusters) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    int maxRetry = 3;
                    for (int attempt = 1; attempt <= maxRetry; attempt++) {
                        try {
                            semaphore.acquire();
                            try {
                                if (!cluster.getPoints().isEmpty()) {
                                    String clusterId = UUID.randomUUID().toString();
                                    List<String> fileIdList = new ArrayList<>();
                                    StringBuilder contentBuilder = new StringBuilder();
                                    for (Point point : cluster.getPoints()) {
                                        int pointIndex = points.indexOf(point);
                                        String fileId = topics.get(pointIndex).get("fileId");
                                        String fileName = topics.get(pointIndex).get("fileName");
                                        String topic = topics.get(pointIndex).get("topic");
                                        String content = topics.get(pointIndex).get("content");
                                        fileIdList.add(fileId);
                                        contentBuilder.append("主题：").append(topic).append(" ")
                                                .append("文档名：").append(fileName).append(" ")
                                                .append("文档内容：").append(content).append("\n");
                                    }

                                    String prompt = String.format(PromptTemplates.Topic_Description_Extraction, contentBuilder);
                                    TopicResult topicResult = chatClient.prompt().user(prompt).call().entity(TopicResult.class);

                                    for (String fileId : fileIdList) {
                                        Triple relTriple = new Triple();
                                        relTriple.setId(UUID.randomUUID().toString()); // 关系唯一ID
                                        relTriple.setSubjectId(clusterId);            // 主体：主题节点ID
                                        relTriple.setObjectId(fileId);                // 客体：文件节点ID
                                        relTriple.setPredicate("包含");                // 关系：包含
                                        relTriple.setSubjectType("主题聚类节点");
                                        relTriple.setObjectType("文档");
                                        relTriple.setType(Triple.Type.RELATION);      // 类型：关系
                                        relationTriplesQueue.add(relTriple);
                                    }

                                    // 构建主题节点 Triple
                                    Triple topicTriple = new Triple();
                                    topicTriple.setId(clusterId);
                                    topicTriple.setSubject(topicResult.clusterName);
                                    topicTriple.setSubjectId(clusterId);
                                    topicTriple.setSubjectType("主题聚类节点");
                                    topicTriple.setType(Triple.Type.PROPERTY);
                                    topicTriple.setPredicate("description");
                                    topicTriple.setObject(topicResult.description);
                                    topicTriplesQueue.add(topicTriple);
                                }
                            } finally {
                                semaphore.release();
                            }
                            break; // 成功就退出重试
                        } catch (Exception e) {
                            if (attempt == maxRetry) {
                                log.error("簇处理失败", e);
                            }
                            try {
                                Thread.sleep(1000L * attempt);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }, executor);
                futures.add(future);
            }

            // 等待所有簇完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();


//            /**
//             * 5.写入单独的主题节点文件
//             */
//            // 输出主题实体 JSONL
//            //TextUtils.writeJsonl(outputPath + "/topicEntities.jsonl", topicTriples);
//            // 追加写入主题实体（不覆盖，直接加到文件末尾）
//            TextUtils.writeJsonlAppend(outputPath + "/all_entities.jsonl", topicTriples);
//
//            /**
//             * 6.写入主题节点与文档节点联系的文件
//             */
//            //TextUtils.writeJsonl(outputPath + "/topicRelations.jsonl", relationTriples);
//            // 追加写入关系数据
//            TextUtils.writeJsonlAppend(outputPath + "/all_relations.jsonl", relationTriples);

            TextUtils.writeJsonlAppend(outputPath + "/all_entities_test1.jsonl", new ArrayList<>(topicTriplesQueue));
            TextUtils.writeJsonlAppend(outputPath + "/all_relations_test1.jsonl", new ArrayList<>(relationTriplesQueue));

            executor.shutdown();
            log.info("主题聚类成功");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("主题聚类失败");
        }
    }

    public void abstractCluster(List<Map<String, String>> nodes) {
        try {
            List<String> abstractList = new ArrayList<>();
            for (Map<String, String> item : nodes) {
                String docAbstract = item.getOrDefault("文档摘要", "");
                if (docAbstract.isEmpty()) {
                    log.info("文档[{}]摘要为空", item.get("id"));
                }
                abstractList.add(docAbstract);
            }

            int batchSize = 10;
            ExecutorService executor = Executors.newFixedThreadPool(10);

            class BatchResult {
                int index;
                List<float[]> vectors;

                BatchResult(int index, List<float[]> vectors) {
                    this.index = index;
                    this.vectors = vectors;
                }
            }

            CompletionService<BatchResult> completionService =
                    new ExecutorCompletionService<>(executor);

            int batchIndex = 0;

            // 1. 提交任务（带 index）
            for (int i = 0; i < abstractList.size(); i += batchSize) {
                int start = i;
                int currentIndex = batchIndex++;

                completionService.submit(() -> {
                    int end = Math.min(start + batchSize, abstractList.size());
                    List<String> batch = abstractList.subList(start, end);
                    List<float[]> vecs = embeddingModel.embed(batch);
                    return new BatchResult(currentIndex, vecs);
                });
            }

            // 2. 收集（先放 Map）
            Map<Integer, List<float[]>> resultMap = new HashMap<>();

            for (int i = 0; i < batchIndex; i++) {
                BatchResult result = completionService.take().get();
                resultMap.put(result.index, result.vectors);
            }

           // 3. 按顺序拼接（关键）
            List<float[]> embeddings = new ArrayList<>();

            for (int i = 0; i < batchIndex; i++) {
                embeddings.addAll(resultMap.get(i));
            }

            executor.shutdown();

            /**
             * 3.层次聚类（余弦相似度）
             */
            //DBSCAN 聚类
            List<Point> points = new ArrayList<>();
            for (float[] embedding : embeddings) {
                points.add(new Point(embedding));
            }

            //构建簇对象（距离阈值，一个簇中最少的点数，距离计算方式）
            DBSCANClusterer<Point> clusterer = new DBSCANClusterer<Point>(THRESHOLD, 0, new CosineDistance());
            List<Cluster<Point>> clusters = clusterer.cluster(points);
            log.info("聚类后的簇数为：[{}]", clusters.size());

            // 遍历每个簇 → 打印你自己的原始数据

            for (int i = 0; i < clusters.size(); i++) {
                Cluster<Point> cluster = clusters.get(i);
                log.info("==================== 第 {} 个簇 ====================", i + 1);

                if (!cluster.getPoints().isEmpty()) {

                    List<String> fileIdList = new ArrayList<>();
                    StringBuilder contentBuilder = new StringBuilder();
                    for (Point point : cluster.getPoints()) {
                        int pointIndex = points.indexOf(point);
                        String fileId = nodes.get(pointIndex).get("id");
                        String fileName = nodes.get(pointIndex).get("name");
                        String summary = nodes.get(pointIndex).get("文档摘要");
                        fileIdList.add(fileId);
                        contentBuilder.append("文档Id：").append(fileId).append(" ")
                                .append("文档名：").append(fileName).append(" ")
                                .append("文档摘要：").append(summary).append("\n");
                    }
                    // ========== 我帮你补上的输出语句 ==========
                    log.info("当前簇包含文档ID：{}", fileIdList);
                    log.info("当前簇所有文档信息：\n{}", contentBuilder);

                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            log.error("摘要聚类错误", e);
        }

    }
}
