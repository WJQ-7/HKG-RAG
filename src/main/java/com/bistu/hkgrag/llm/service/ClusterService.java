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
import org.apache.commons.text.similarity.CosineDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJson;
import static com.bistu.hkgrag.preprocess.utils.TextUtils.writeJsonl;

@Service
public class ClusterService {
    private static Logger log = LoggerFactory.getLogger(ClusterService.class);
    private EmbeddingModel embeddingModel;
    private ChatClient chatClient;
    //余弦距离阈值
    private static final double THRESHOLD = 0.25;

    public ClusterService(@Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient) {
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
            List<DoFile> fileList = TextUtils.readJsonlToList(inputPath,DoFile.class);
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
            ExecutorService executor_embedding = Executors.newFixedThreadPool(10);

            CompletionService<List<float[]>> completionService =
                    new ExecutorCompletionService<>(executor_embedding);

            for (int i = 0; i < topicList.size(); i += batchSize) {
                int start = i;
                completionService.submit(() -> {
                    int end = Math.min(start + batchSize, topicList.size());
                    List<String> batch = new ArrayList<>(topicList.subList(start, end));
                    return embeddingModel.embed(batch);
                });
            }

            List<float[]> embeddings = new ArrayList<>();

            int taskCount = (topicList.size() + batchSize - 1) / batchSize;

            for (int i = 0; i < taskCount; i++) {
                Future<List<float[]>> future = completionService.take(); // 谁先完成拿谁
                embeddings.addAll(future.get());
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
//            Map<Point, String> pointToParentId = new HashMap<>();
//            // Map<String,List<String>> clusterIdToFileIds =new HashMap<>();
//            List<Triple> topicTriples = new ArrayList<>();
//            List<Triple> relationTriples = new ArrayList<>();
//            for (Cluster<Point> cluster : clusters) {
//                String clusterId = UUID.randomUUID().toString();
//                String clusterName = "";
//                if (!cluster.getPoints().isEmpty()) {
//                    List<String> contentList = new ArrayList<>();
//                    // 遍历当前簇里的所有点（节点） 收集这个簇里的所有fileId
//                    List<String> fileIdList = new ArrayList<>();
//                    StringBuilder contentBuilder = new StringBuilder();
//                    for (Point point : cluster.getPoints()) {
//                        //点到簇
//                        pointToParentId.put(point, clusterId);
//                        //这个簇对应第几个话题
//                        int pointIndex = points.indexOf(point);
//                        String fileId = topics.get(pointIndex).get("fileId");
//                        String fileName = topics.get(pointIndex).get("fileName");
//                        String topic = topics.get(pointIndex).get("topic");
//                        String content = topics.get(pointIndex).get("content");
//                        fileIdList.add(fileId);
//                        contentBuilder.append("主题：").append(topic).append(" ");
//                        contentBuilder.append("文档名：").append(fileName).append(" ");
//                        contentBuilder.append("文档内容：").append(content).append("\n");
//                    }
//                    String prompt=String.format(PromptTemplates.Topic_Description_Extraction,contentBuilder);
//                    TopicResult topicResult=chatClient.prompt().user(prompt).call().entity(TopicResult.class);
//
//                    //簇Id->该簇下所有的文件Id
//                    //  clusterIdToFileIds.put(clusterId,fileIdList);
//                    for (String fileId : fileIdList) {
//                        Triple relTriple = new Triple();
//                        relTriple.setId(UUID.randomUUID().toString()); // 关系唯一ID
//                        relTriple.setSubjectId(clusterId);            // 主体：主题节点ID
//                        relTriple.setObjectId(fileId);                // 客体：文件节点ID
//                        relTriple.setPredicate("包含");                // 关系：包含
//                        relTriple.setSubjectType("主题聚类节点");
//                        relTriple.setObjectType("文档");
//                        relTriple.setType(Triple.Type.RELATION);      // 类型：关系
//                        relationTriples.add(relTriple);
//                    }
//                    //TODO 考虑主题节点的名字 1.采用簇中第一个topic 2.给模型簇下所有文件的名字和主题让模型生成
//                    //生成话题节点
//                    int idx = points.indexOf(cluster.getPoints().get(0));
//                    clusterName = topicList.get(idx);
//                    //构建话题节点
//                    Triple topicTriple = new Triple();
//                    topicTriple.setId(clusterId);
//                    topicTriple.setSubject(topicResult.clusterName);
//                    topicTriple.setSubjectId(clusterId);
//                    topicTriple.setSubjectType("主题聚类节点");
//                    topicTriple.setType(Triple.Type.PROPERTY);
//                    topicTriple.setPredicate("description");
//                    topicTriple.setObject(topicResult.description);
//                    topicTriples.add(topicTriple);
//                }
//
//            }
//            log.info("构建[聚类节点]->[文档]的关系个数为：[{}]",relationTriples.size());

            Semaphore semaphore = new Semaphore(5); // 限流，控制同时调用模型的簇数
            ExecutorService executor = Executors.newFixedThreadPool(5);

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

            TextUtils.writeJsonlAppend(outputPath + "/all_entities.jsonl", new ArrayList<>(topicTriplesQueue));
            TextUtils.writeJsonlAppend(outputPath + "/all_relations.jsonl", new ArrayList<>(relationTriplesQueue));

            executor.shutdown();
            log.info("主题聚类成功");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("主题聚类失败");
        }
    }
}
