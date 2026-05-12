package com.bistu.hkgrag.WorkFlow;


import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.model.Triple;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
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

import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJsonlToTriple;

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


    /**
     * 向Milvus数据库中插入内容实体
     */
    @Test
    @Order(1)
    public void testMilvusEntityService() {
        String inputContentEntitiesPath = "src/main/resources/data/all_entities.jsonl";

        //批量插入的数量
        int batchSize = 400;
        int embed_batchSize = 10;
        Gson gson = new Gson();
        Set<String> existedSubjectIds = new HashSet<>();
        List<DoEntity> batchList = new ArrayList<>(batchSize);
        int totalEntities = 0;

        //总插入数
        int totalInserted = 0;
        //可插入的数量
        int totalRead = 0;
        //创建表
        entityService.createEntityCollection();
        //1.读取文件
        try (BufferedReader reader = Files.newBufferedReader(Path.of(inputContentEntitiesPath))) {
            String line;
            //2.映射成数据库的model
            while ((line = reader.readLine()) != null) {
                totalRead++;
                JsonObject json = gson.fromJson(line, JsonObject.class);
                //如果抽取结果存在subjectId或主实体名为空（需要对文档名做嵌入）的情况则跳过这条数据
                if (json.get("subjectId") == null || json.get("subject") == null) continue;
                String subjectId = json.get("subjectId").getAsString();
                //针对subjectId去重
                if (!existedSubjectIds.add(subjectId)) continue;
                //构造实体
                DoEntity entity = new DoEntity();
                entity.setEntityId(subjectId);
                entity.setEntityName(json.get("subject").getAsString());
                batchList.add(entity);
                //达到批量的数据执行一次插入
                if (batchList.size() >= batchSize) {
                    try {
                        //3. 插入数据
                        //3.1 embedding
                        List<String> names = batchList.stream()
                                .map(DoEntity::getEntityName)
                                .collect(Collectors.toList());

                        List<float[]> vectors = new ArrayList<>(names.size());
                        for (int i = 0; i < names.size(); i += embed_batchSize) {
                            int end = Math.min(i + embed_batchSize, names.size());
                            List<String> subList = names.subList(i, end);
                            List<float[]> subVectors = embeddingModel.embed(subList);
                            vectors.addAll(subVectors);
                        }
                        //3.2 填充向量
                        for (int i = 0; i < batchList.size(); i++) {
                            batchList.get(i).setEntityNameVector(vectors.get(i));
                        }
                        //3.3 插入
                        Boolean success = entityService.insertEntities(batchList);
                        if (success) {
                            totalInserted += batchList.size();
                            System.out.println("成功插入: " + batchList.size());
                        } else {
                            System.out.println("插入失败 batch");
                        }

                    } catch (Exception e) {
                        System.out.println("批量插入实体失败" + e.getMessage());
                        e.printStackTrace();
                    }
                    batchList.clear();
                }
            }
            // 处理最后一批
            if (!batchList.isEmpty()) {
                try {
                    List<String> names = batchList.stream()
                            .map(DoEntity::getEntityName)
                            .collect(Collectors.toList());

                    List<float[]> vectors = new ArrayList<>(names.size());
                    for (int i = 0; i < names.size(); i += embed_batchSize) {
                        int end = Math.min(i + embed_batchSize, names.size());
                        List<String> subList = names.subList(i, end);
                        List<float[]> subVectors = embeddingModel.embed(subList);
                        vectors.addAll(subVectors);
                    }

                    for (int i = 0; i < batchList.size(); i++) {
                        batchList.get(i).setEntityNameVector(vectors.get(i));
                    }

                    Boolean success = entityService.insertEntities(batchList);

                    if (success) {
                        totalInserted += batchList.size();
                        System.out.println("成功插入(最后一批): " + batchList.size());
                    } else {
                        System.out.println("[最后一批]插入失败，数据量：: " + batchList.size());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            System.out.println("========== 完成 ==========");
            System.out.println("读取总数: " + totalRead);
            System.out.println("去重后: " + existedSubjectIds.size());
            System.out.println("成功插入: " + totalInserted);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }


    /**
     * 向Milvus数据库中插入文件实体
     */
    @Test
    @Order(2)
    public void testMilvusFileService() {
        String inputPath = "src/main/resources/data/chunk_topic.jsonl";

        int batchSize = 100;
        int embed_batchSize = 10;

        Gson gson = new Gson();

        Set<String> existedChunkIds = new HashSet<>();
        List<DoFile> batchList = new ArrayList<>(batchSize);

        int totalFiles = 0;

        try (BufferedReader reader = Files.newBufferedReader(Path.of(inputPath))) {

            // ✅ 只创建一次表
            fileService.createFileCollection();

            String line;

            while ((line = reader.readLine()) != null) {

                JsonObject json = gson.fromJson(line, JsonObject.class);

                if (json.get("chunkId") == null) continue;

                String chunkId = json.get("chunkId").getAsString();

                // 去重
//                if (!existedChunkIds.add(chunkId)) continue;
                if (!existedChunkIds.add(chunkId)) {
                    System.out.println("发现重复 chunkId：" + chunkId);
                    continue;
                }

                DoFile file = new DoFile();
                file.setFileId(json.get("fileId").getAsString());
                file.setChunkId(chunkId);
                file.setFileName(json.get("fileName").getAsString());
                file.setTopic(json.get("topic").getAsString());
                file.setContent(json.get("content").getAsString());

                batchList.add(file);

                // 满批处理
                if (batchList.size() >= batchSize) {
                    try {
                        //topic分批embedding
                        List<String> topics = batchList.stream()
                                .map(DoFile::getTopic)
                                .collect(Collectors.toList());
                        List<float[]> topicVectors = new ArrayList<>();
                        for (int i = 0; i < topics.size(); i += embed_batchSize) {
                            int end = Math.min(i + embed_batchSize, topics.size());
                            topicVectors.addAll(embeddingModel.embed(topics.subList(i, end)));
                        }
                        //content分批embedding
                        List<String> contents = batchList.stream()
                                .map(DoFile::getContent).collect(Collectors.toList());
                        List<float[]> contentVectors = new ArrayList<>();
                        for (int i = 0; i < contents.size(); i += embed_batchSize) {
                            int end = Math.min(i + embed_batchSize, contents.size());
                            contentVectors.addAll(embeddingModel.embed(contents.subList(i, end)));
                        }
                        if (topicVectors.size() != batchList.size()
                                || contentVectors.size() != batchList.size()) {
                            throw new RuntimeException("embedding数量不一致！");
                        }
                        //填充列表
                        for (int i = 0; i < batchList.size(); i++) {
                            batchList.get(i).setTopicVector(topicVectors.get(i));
                            batchList.get(i).setContentVector(contentVectors.get(i));
                        }
                        //插入表
                        boolean success = fileService.insertFiles(batchList);

                        if (success) {
                            System.out.println("批量插入文件成功：" + batchList.size());
                        } else {
                            System.out.println("批量插入文件失败");
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
                    for (int i = 0; i < topics.size(); i += embed_batchSize) {
                        int end = Math.min(i + embed_batchSize, topics.size());
                        topicVectors.addAll(
                                embeddingModel.embed(topics.subList(i, end))
                        );
                    }

                    List<String> contents = batchList.stream()
                            .map(DoFile::getContent)
                            .collect(Collectors.toList());

                    List<float[]> contentVectors = new ArrayList<>();
                    for (int i = 0; i < contents.size(); i += embed_batchSize) {
                        int end = Math.min(i + embed_batchSize, contents.size());
                        contentVectors.addAll(
                                embeddingModel.embed(contents.subList(i, end))
                        );
                    }

                    for (int i = 0; i < batchList.size(); i++) {
                        batchList.get(i).setTopicVector(topicVectors.get(i));
                        batchList.get(i).setContentVector(contentVectors.get(i));
                    }

                    boolean success = fileService.insertFiles(batchList);

                    if (success) {
                        totalFiles += batchList.size();
                        System.out.println("最后一批插入成功：" + batchList.size());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            System.out.println("========== 完成 ==========");
            System.out.println("去重后chunk数：" + existedChunkIds.size());
            System.out.println("成功插入文件数：" + totalFiles);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 向Milvus数据库中插入聚类实体
     */
    @Test
    @Order(3)
    public void testMilvusClusterService() {
        String inputPath = "src/main/resources/data/all_entities.jsonl";
        try{
            List<Triple> triples = readJsonlToTriple(inputPath);

            Map<String, DoCluster> clusterMap = new HashMap<>();
            List<DoCluster> clusters = new ArrayList<>();
            //先创建表
            clusterService.createClusterCollection();
            for (Triple triple : triples) {
                if (!"主题聚类节点".equals(triple.getSubjectType())) {
                    continue;
                }
                String subjectId = triple.getSubjectId();
                if (!clusterMap.containsKey(subjectId)) {
                    DoCluster cluster = new DoCluster();
                    cluster.setClusterId(subjectId);
                    cluster.setClusterName(triple.getSubject());
                    cluster.setDescription(triple.getObject());
                    clusters.add(cluster);
                    clusterMap.put(subjectId, cluster);
                }
            }
            System.out.println("待插入的聚类节点总数：" + clusterMap.size());
            int embed_batchSize = 10;
            for (int i = 0; i < clusters.size(); i += embed_batchSize) {
                int end = Math.min(i + embed_batchSize, clusters.size());
                List<float[]> clusterNameVectors = new ArrayList<>();
                List<float[]> clusterDescriptionVectors = new ArrayList<>();

                List<DoCluster> batchList = clusters.subList(i, end);
                List<String> clusterNameList = batchList.stream()
                        .map(DoCluster::getClusterName)
                        .collect(Collectors.toList());
                List<String> clusterDescriptionList = batchList.stream()
                        .map(DoCluster::getDescription)
                        .collect(Collectors.toList());

                clusterNameVectors.addAll(embeddingModel.embed(clusterNameList));
                clusterDescriptionVectors.addAll(embeddingModel.embed(clusterDescriptionList));
                for (int j = 0; j < batchList.size(); j++) {
                    DoCluster cluster = batchList.get(j);
                    cluster.setClusterNameVector(clusterNameVectors.get(j));
                    cluster.setDescriptionVector(clusterDescriptionVectors.get(j));
                }
                boolean success = clusterService.insertClusters(batchList);
                if (success) {
                    System.out.println("批量插入文件成功：" + batchList.size());
                } else {
                    System.err.println("批量插入失败，批次起始下标：" + i + "批次大小：" + batchList.size());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("向量库中插入聚类实体异常");
        }
    }


    /**
     * 2.图数据库
     */

    /**
     * 文档节点和文档内容节点的插入方法一致
     *
     * @param inputPath 待插入节点文件路径
     */
    private void processNodeFile(String inputPath) {
        try {
            List<Triple> triples = readJsonlToTriple(inputPath);

            Map<String, Map<String, Object>> mapMap = new HashMap<>();
            for (Triple triple : triples) {
                String subjectId = triple.getSubjectId();
                mapMap.putIfAbsent(subjectId, new HashMap<>());
                Map<String, Object> map = mapMap.get(subjectId);
                map.put("label", triple.getSubjectType());
                map.put("id", subjectId);
                map.put("name", triple.getSubject());
                if (triple.getPredicate() != null && !triple.getPredicate().equals("")) {
                    map.put(triple.getPredicate(), triple.getObject());
                }

            }
            int successCount = 0;
            List<String> failedIds = new ArrayList<>();
            for (Map<String, Object> props : mapMap.values()) {
                Boolean success = tripleService.insertNode(props);
                if (Boolean.TRUE.equals(success)) {
                    successCount++;
                } else {
                    failedIds.add((String) props.get("id"));
                }
            }
            System.out.println("========== 插入结果 ==========");
            System.out.println("待插入总数：" + mapMap.size());
            System.out.println("成功数量：" + successCount);
            System.out.println("失败数量：" + failedIds.size());

            if (!failedIds.isEmpty()) {
                System.out.println("失败ID列表：");
                failedIds.forEach(System.out::println);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("插入文件节点失败");
        }
    }

    /**
     * 在neo4j中插入节点
     */
    @Test
    @Order(4)
    public void test_createFileNode() {
        String inputPath = "src/main/resources/data/all_entities.jsonl";
        processNodeFile(inputPath);

    }

    /**
     * 插入关系
     */
    @Test
    @Order(5)
    public void test_createRelationship() {

        String inputFilePath = "src/main/resources/data/all_relations.jsonl";
        //待处理总数
        int totalCount = 0;
        //有效关系数量（ID不为空）
        int validCount = 0;
        //成功插入
        int successCount = 0;
        //抽取信息有错得列表
        List<String> invalidIds = new ArrayList<>();
        //失败列表
        List<String> failedIds = new ArrayList<>();

        try {

            List<Triple> relationList = readJsonlToTriple(inputFilePath);
            totalCount = relationList.size();
            for (Triple triple : relationList) {
                if ((triple.getSubjectId() != null && !triple.getSubjectId().equals(""))
                        && (triple.getObjectId() != null && !triple.getObjectId().equals(""))
                        && (triple.getSubjectType() != null && !triple.getSubjectType().equals(""))
                        && (triple.getObjectType() != null && !triple.getObjectType().equals(""))
                        && (triple.getPredicate() != null && !triple.getPredicate().equals(""))) {
                    validCount++;
                    boolean success = tripleService.createRelationship(triple);

                    if (success) {
                        successCount++;
                    } else {
                        failedIds.add(triple.getId());
                    }
                } else{
                    invalidIds.add(triple.getId());
                }


            }
            // ===== 标准输出 =====
            System.out.println("========== 关系插入结果 ==========");
            System.out.println("总数据量：" + totalCount);
            System.out.println("有效关系数：" + validCount);
            System.out.println("成功插入：" + successCount);
            System.out.println("失败数量：" + failedIds.size());
            System.out.println("无效数据（ID缺失）：" + invalidIds.size());

            Assertions.assertEquals(validCount, successCount);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

//    @Test
//    public void test(){
//        String input="你好";
//        float[] t=embeddingModel.embed(input);
//        System.out.println(t);
//    }

}
