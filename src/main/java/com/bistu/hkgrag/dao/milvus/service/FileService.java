package com.bistu.hkgrag.dao.milvus.service;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * File数据库(Milvus)表的操作类
 *
 * @author wangjingqi
 */
@Slf4j
@Service
public class FileService {
    //定义file表
    private static final String COLLECTION_NAME = "file";
    //定义向量维度
    private static final int VECTOR_DIM = 2560;
    private final MilvusClientV2 client;
    private final EmbeddingModel embeddingModel;
    // 精确匹配权重 a
    @Value("${HKG-RAG.retrieval.EXACT-WEIGHT}")
    float EXACT_WEIGHT;
    // 向量匹配权重 b
    @Value("${HKG-RAG.retrieval.VECTOR_WEIGHT}")
    float VECTOR_WEIGHT;

    public FileService(MilvusClientV2 client,
                       @Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
    }


    /**
     * 判断表是否存在
     *
     * @return 返回表是否存在
     */
    public boolean hasCollection() {
        return client.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build());
    }

    /**
     * 创建file collection
     * Collection（集合，类似 MySQL 表）、Field（字段，类似表列）、Vector（向量，核心检索数据）
     */
    public boolean createFileCollection() {
        try {
            if (hasCollection()) {
                log.info("[{}]表已经存在", COLLECTION_NAME);
                return true;
            }

            List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();

            //文件ID
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("fileId")
                    .dataType(DataType.VarChar)
                    .maxLength(64)
                    .build());
            //chunkID
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("chunkId")
                    .dataType(DataType.VarChar)
                    .isPrimaryKey(true)
                    .maxLength(64)
                    .build());
            //文件名fileName
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("fileName")
                    .dataType(DataType.VarChar)
                    .maxLength(512).
                    build());
            //分片主题topic
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("topic")
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .build());
            //文件内容content
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("content")
                    .dataType(DataType.VarChar)
                    .maxLength(28000)
                    .build());
            //主题向量 topicVector
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("topicVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //文档内容向量 contentVector
            fields.add(CreateCollectionReq.FieldSchema.builder()
                    .name("contentVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //构建主题向量和文档内容向量索引
            List<IndexParam> indexParams = new ArrayList<>();
            indexParams.add(IndexParam.builder()
                    .fieldName("topicVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("contentVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("content")
                    .indexType(IndexParam.IndexType.INVERTED)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("topic")
                    .indexType(IndexParam.IndexType.INVERTED)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
            //构建CollectionSchema
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(fields).build();
            //构建创建Collection请求
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();
            //使用Client发送请求
            client.createCollection(req);
            log.info("[{}]表创建成功", COLLECTION_NAME);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表创建失败", COLLECTION_NAME);
            return false;
        }
    }

    /**
     * 判断当前表中是否已经存在chunkId
     *
     * @param chunkId 待查询的文件ID
     * @return 是否已经存在
     */
    public Boolean isFileIdExist(String chunkId) {
        if (chunkId == null) {
            log.info("chunkId为空");
            return false;
        }
        QueryReq queryReq = QueryReq.builder()
                .collectionName(COLLECTION_NAME)
                .ids(Collections.singletonList(chunkId))
                .outputFields(Collections.singletonList("chunkId"))
                .limit(1)
                .build();
        QueryResp queryResp = client.query(queryReq);
        return !queryResp.getQueryResults().isEmpty();
    }

    /**
     * 插入单条文件数据
     *
     * @param file 待插入文件
     * @return 是否插入成功
     */
    public Boolean insertFile(DoFile file) {
        if (file.getChunkId() == null) {
            log.info("文件chunkId为空");
            return false;
        }
        if (isFileIdExist(file.getChunkId())) {
            log.info("文件chunkId已经存在");
            return false;
        }
        try {
            JsonObject row = new JsonObject();
            row.addProperty("fileId", file.getFileId());
            row.addProperty("chunkId", file.getChunkId());
            row.addProperty("fileName", file.getFileName());
            row.addProperty("topic", file.getTopic());
            row.addProperty("content", file.getContent());
            row.add("topicVector", new Gson().toJsonTree(file.getTopicVector()));
            row.add("contentVector", new Gson().toJsonTree(file.getContentVector()));
            InsertReq insertReq = InsertReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .data(Collections.singletonList(row))
                    .build();
            InsertResp insertResp = client.insert(insertReq);
            long insertCount = insertResp.getInsertCnt();
            log.info("成功插入[{}]行", insertCount);
            return insertCount > 0;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("文件实体[{}]插入失败", file.getFileId());
            return false;
        }
    }

    /**
     * 批量插入文件数据
     *
     * @param files 待插入文件列表
     * @return 是否插入成功
     */
    public Boolean insertFiles(List<DoFile> files) {
        if (files == null || files.isEmpty()) {
            log.info("批量插入实体列表为空");
            return false;
        }
        try {
            List<JsonObject> data = new ArrayList<>();
            for (DoFile file : files) {
                JsonObject row = new JsonObject();
                row.addProperty("fileId", file.getFileId());
                row.addProperty("chunkId", file.getChunkId());
                row.addProperty("fileName", file.getFileName());
                row.addProperty("topic", file.getTopic());
                row.addProperty("content", file.getContent());
                row.add("topicVector", new Gson().toJsonTree(file.getTopicVector()));
                row.add("contentVector", new Gson().toJsonTree(file.getContentVector()));
                data.add(row);
            }
            //创建插入请求
            InsertReq insertReq = InsertReq.builder().collectionName(COLLECTION_NAME).data(data).build();
            //使用客户端发送请求
            client.insert(insertReq);
            log.info("[{}]表成功插入{}条实体数据", COLLECTION_NAME, data.size());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表批量插入实体失败", COLLECTION_NAME);
            return false;
        }
    }

    /**
     * 批量查找 根据文件id获取文件的所有信息
     *
     * @param fileIds 待查询的文件id列表
     * @return 所有文档信息
     */
    public List<DoFile> getFilesById(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            log.info("待检索得fileId为空");
            return Collections.emptyList();
        }
        try {
            String filter = "fileId in [" +
                    fileIds.stream()
                            .map(id -> "\"" + id + "\"")
                            .collect(Collectors.joining(",")) +
                    "]";

            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter(filter)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content")) // 按需返回字段
                    .build();

            QueryResp resp = client.query(queryReq);
            List<DoFile> fileList = new ArrayList<>();
            for (QueryResp.QueryResult qr : resp.getQueryResults()) {
                Map<String, Object> map = qr.getEntity();
                DoFile file = new DoFile();
                file.setFileId(map.get("fileId").toString());
                file.setContent(map.get("content").toString());
                file.setTopic(map.get("topic").toString());
                file.setChunkId(map.get("chunkId").toString());
                file.setFileName(map.get("fileName").toString());
                fileList.add(file);
            }
            log.info("[{}]表根据fileId检索文档成功，数量：{}", COLLECTION_NAME, fileList.size());
            return fileList;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表根据fileId检索文档失败", COLLECTION_NAME);
            return Collections.emptyList();
        }

    }

    /**
     * 根据指定的fileId范围与问题进行向量匹配
     * @param query 问题
     * @param fileIds 相关文档的id范围
     * @param topK
     * @return 相关文档
     */
    public List<DoFile> searchFilesByIds(String query, List<String> fileIds, int topK) {
        if (fileIds == null || fileIds.isEmpty()) {
            log.info("待检索 fileIds 为空");
            return Collections.emptyList();
        }
        if (topK < 1) {
            log.info("通过文档话题进行精确检索的topK小于1");
            return Collections.emptyList();
        }
        try {
            // 1. query 转 embedding】
            float[] queryEmbedding = embeddingModel.embed(query);
            List<Float> vector = IntStream.range(0, queryEmbedding.length)
                    .mapToObj(i -> queryEmbedding[i])
                    .collect(Collectors.toList());

            // 2. 拼接 filter
            String filter = "fileId in [" +
                    fileIds.stream()
                            .map(id -> "\"" + id + "\"")
                            .collect(Collectors.joining(",")) +
                    "]";

            log.info("Milvus filter: {}", filter);

            // 3. 构造搜索请求
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("contentVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .filter(filter)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
                    .build();


            // 4. 执行向量搜索
            SearchResp searchResp = client.search(searchReq);
            List<DoFile> resultList = new ArrayList<>();
            // 5. 解析结果
            List<SearchResp.SearchResult> results = searchResp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoFile file = new DoFile();
                file.setFileId(map.get("fileId").toString());
                file.setChunkId(map.get("chunkId").toString());
                file.setFileName(map.get("fileName").toString());
                file.setTopic(map.get("topic").toString());
                file.setContent(map.get("content").toString());
                file.setTopicScore(score);
                resultList.add(file);
            }
            log.info("[{}] 指定 fileId 范围向量检索成功，返回数量：{}",COLLECTION_NAME,resultList.size());
            return resultList;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表根据id范围进行检索出错", COLLECTION_NAME);
            return Collections.emptyList();
        }

    }


    /**
     * 对文档话题进行精确检索
     *
     * @param topic 待检索的话题
     * @param topK    取前K个
     * @return
     */
    public List<DoFile> exactSearchByTopic(String topic, int topK) {
        if (topK < 1) {
            log.info("通过文档话题进行精确检索的topK小于1");
            return Collections.emptyList();
        }
        if (topic == null || topic.isEmpty()) {
            log.info("待进行精确检索的文档话题为空");
            return Collections.emptyList();
        }
        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("topic == \"" + topic + "\"")
                    .limit(topK)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
                    .build();

            QueryResp resp = client.query(queryReq);
            // 精确检索结果
            List<QueryResp.QueryResult> exactResults = resp.getQueryResults();
            List<DoFile> files = new ArrayList<>();

            // 遍历精确结果（query 没有 score，只有向量搜索才有）
            for (QueryResp.QueryResult queryResult : exactResults) {
                DoFile file = new DoFile();
                file.setFileId(queryResult.getEntity().get("fileId").toString());
                file.setChunkId(queryResult.getEntity().get("chunkId").toString());
                file.setFileName(queryResult.getEntity().get("fileName").toString());
                file.setTopic(queryResult.getEntity().get("topic").toString());
                file.setContent(queryResult.getEntity().get("content").toString());
                file.setTopicScore(1.0f);
                files.add(file);
            }
            return files;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对话题字段进行精确匹配时出错", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }


    /**
     * 根据话题对文件进行向量检索
     *
     * @param topic 待检索的内容
     * @param topK    取最相似的前K个
     * @return
     */
    public List<DoFile> vectorSearchByTopic(String topic, int topK) {
        if (topK < 1) {
            log.info("通过文档话题进行向量检索的topK小于1");
            return Collections.emptyList();
        }
        if (topic == null || topic.isEmpty()) {
            log.info("待进行向量检索的话题为空");
            return Collections.emptyList();
        }
        try {
            float[] topicVector = embeddingModel.embed(topic);
            List<Float> vector = IntStream.range(0, topicVector.length)
                    .mapToObj(i -> topicVector[i])
                    .collect(Collectors.toList());

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("topicVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoFile> files = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoFile file = new DoFile();
                file.setFileId(map.get("fileId").toString());
                file.setChunkId(map.get("chunkId").toString());
                file.setFileName(map.get("fileName").toString());
                file.setTopic(map.get("topic").toString());
                file.setContent(map.get("content").toString());
                file.setTopicScore(score);
                files.add(file);
            }
            return files;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对文件话题进行向量检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }

    }

    /**
     * 对文件话题进行混合检索（对topic字段的精确匹配和对topicVector字段的相似度匹配）
     *
     * @param topic 待检索的文档话题
     * @return 相似文件列表
     */
    public List<DoFile> hybridSearchByTopic(String topic, int topK) {
        if (topic == null || topic.isEmpty()) {
            log.info("待检索的话题为空");
            return Collections.emptyList();
        }
        if (topK < 1) {
            log.info("topK小于1");
            return Collections.emptyList();
        }
        try {
            //1.进行精确匹配
            List<DoFile> exactList = exactSearchByTopic(topic, 2 * topK);
            //2.进行向量检索
            List<DoFile> vectorList = vectorSearchByTopic(topic, 2 * topK);
            //3.收集结果并进行去重
            Map<String, DoFile> entityMap = new HashMap<>();

            // 处理精确匹配结果
            for (DoFile exactFile : exactList) {
                // 加权分数：a*精确分 + b*向量分（精确匹配默认向量分为0）
                float finalScore = EXACT_WEIGHT * exactFile.getTopicScore();
                exactFile.setTopicScore(finalScore);
                //TODO 考虑用chunkId还是fileId
                entityMap.put(exactFile.getChunkId(), exactFile);
            }

            // 处理向量匹配结果（已存在则更新分数，不存在则新增）
            for (DoFile vecFile : vectorList) {
                String chunkId = vecFile.getChunkId();
                if (entityMap.containsKey(chunkId)) {
                    // 已存在（精确+向量都命中）：累加向量权重分数
                    DoFile exist = entityMap.get(chunkId);
                    float newScore = exist.getTopicScore() + VECTOR_WEIGHT * vecFile.getTopicScore();
                    exist.setTopicScore(newScore);
                } else {
                    // 不存在：仅向量分数
                    float finalScore = VECTOR_WEIGHT * vecFile.getTopicScore();
                    vecFile.setTopicScore(finalScore);
                    entityMap.put(chunkId, vecFile);
                }
            }

            // 4. 排序：按最终得分降序
            List<DoFile> files = new ArrayList<>(entityMap.values());
            files.sort((e1, e2) -> Float.compare(e2.getTopicScore(), e1.getTopicScore()));

            // 5. 截断到 topK
            if (files.size() > topK) {
                files = files.subList(0, topK);
            }
            log.info("[{}]表对话题[{}]混合搜索完成，结果数:{}", COLLECTION_NAME, topic, files.size());
            return files;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对文件话题[{}]混合检索失败", COLLECTION_NAME, topic);
            return Collections.emptyList();
        }
    }

    /**
     * 对内容进行精确检索
     *
     * @param content 待检索的内容
     * @param topK    取前K个
     * @return
     */
    public List<DoFile> exactSearchByContent(String content, int topK) {
        if (topK < 1) {
            log.info("通过内容进行精确检索的topK小于1");
            return Collections.emptyList();
        }
        if (content == null || content.isEmpty()) {
            log.info("待进行精确检索的内容为空");
            return Collections.emptyList();
        }
        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("content == \"" + content + "\"")
                    .limit(topK)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
                    .build();

            QueryResp resp = client.query(queryReq);
            // 精确检索结果
            List<QueryResp.QueryResult> exactResults = resp.getQueryResults();
            List<DoFile> files = new ArrayList<>();

            // 遍历精确结果（query 没有 score，只有向量搜索才有）
            for (QueryResp.QueryResult queryResult : exactResults) {
                DoFile file = new DoFile();
                file.setFileId(queryResult.getEntity().get("fileId").toString());
                file.setChunkId(queryResult.getEntity().get("chunkId").toString());
                file.setFileName(queryResult.getEntity().get("fileName").toString());
                file.setTopic(queryResult.getEntity().get("topic").toString());
                file.setContent(queryResult.getEntity().get("content").toString());
                file.setContentScore(1.0f);
                files.add(file);
            }
            return files;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对内容字段进行精确匹配时出错", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

//    public List<DoFile> bm25SearchByContent(String content, int topK) {
//        if (topK < 1) {
//            log.info("通过内容进行BM25检索的topK小于1");
//            return Collections.emptyList();
//        }
//        if (content == null || content.isEmpty()) {
//            log.info("待进行BM25检索的内容为空");
//            return Collections.emptyList();
//        }
//        try{
//            // 1. 构建 BM25 检索请求（Milvus v2 版本）
//            QueryReq queryReq = QueryReq.builder()
//                    .collectionName(COLLECTION_NAME)
//                    .filter("content MATCH '" + content + "'")  // BM25 关键词检索
//                    .limit(topK)
//                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
//                    .build();
//
//            QueryResp resp = client.query(queryReq);
//            List<DoFile> resultList = new ArrayList<>(); SearchReq searchReq = SearchReq.builder()
//                    .collectionName(COLLECTION_NAME)
//                    // 核心！！！指定文本字段，让Milvus做BM25关键词搜索
//                    .weightFieldName("content")
//                    // 搜索文本
//                    .data(Collections.singletonList(content))
//                    .topK(topK)
//                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
//                    .build();
//
//            for (QueryResp.QueryResult queryResult : resp.getQueryResults()) {
//                DoFile file = new DoFile();
//                file.setFileId(queryResult.getEntity().get("fileId").toString());
//                file.setChunkId(queryResult.getEntity().get("chunkId").toString());
//                file.setFileName(queryResult.getEntity().get("fileName").toString());
//                file.setTopic(queryResult.getEntity().get("topic").toString());
//                file.setContent(queryResult.getEntity().get("content").toString());
//
//                // BM25 相关性分数（自动计算）
//                file.setContentScore(queryResult.getScore());
//
//                resultList.add(file);
//            }
//
//            log.info("[{}]表BM25检索完成，关键词：{}，结果数：{}", COLLECTION_NAME, content, files.size());
//            return files;
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("[{}]表进行BM25检索失败", COLLECTION_NAME);
//            return Collections.emptyList();
//        }
//
//    }


    /**
     * 根据内容对文件进行向量检索
     *
     * @param content 待检索的内容
     * @param topK    取最相似的前K个
     * @return
     */
    public List<DoFile> vectorSearchByContent(String content, int topK) {
        if (topK < 1) {
            log.info("通过内容进行向量检索的topK小于1");
            return Collections.emptyList();
        }
        if (content == null || content.isEmpty()) {
            log.info("待进行向量检索的内容为空");
            return Collections.emptyList();
        }
        try {
            float[] contentVector = embeddingModel.embed(content);
            List<Float> vector = IntStream.range(0, contentVector.length)
                    .mapToObj(i -> contentVector[i])
                    .collect(Collectors.toList());

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("contentVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("fileId", "chunkId", "fileName", "topic", "content"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoFile> files = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoFile file = new DoFile();
                file.setFileId(map.get("fileId").toString());
                file.setChunkId(map.get("chunkId").toString());
                file.setFileName(map.get("fileName").toString());
                file.setTopic(map.get("topic").toString());
                file.setContent(map.get("content").toString());
                file.setContentScore(score);
                files.add(file);
            }
            log.info("[{}]表对内容向量检索完成，结果数：{}", COLLECTION_NAME,files.size());
            return files;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对[{}]文件内容进行向量检索失败", COLLECTION_NAME,content);
            return Collections.emptyList();
        }

    }


    /**
     * 对文档内容进行混合检索（对content字段得精确匹配和对contentVector字段的相似度匹配）
     *
     * @param content 待检索的内容
     * @return 检索结果列表
     */
    public List<DoFile> hybridSearchByContent(String content, int topK) {
        if (content == null || content.isEmpty()) {
            log.info("待检索的内容为空");
            return Collections.emptyList();
        }
        // 安全校验：topK 不能小于 1
        if (topK < 1) {
            log.info("topK小于1");
            return Collections.emptyList();
        }
        try {
            //1.进行精确匹配
            List<DoFile> exactList = exactSearchByContent(content, 2 * topK);
            //2.进行向量检索
            List<DoFile> vectorList = vectorSearchByContent(content, 2 * topK);
            //3.收集结果并进行去重
            Map<String, DoFile> entityMap = new HashMap<>();

            // 处理精确匹配结果
            for (DoFile exactFile : exactList) {
                // 加权分数：a*精确分 + b*向量分（精确匹配默认向量分为0）
                float finalScore = EXACT_WEIGHT * exactFile.getContentScore();
                exactFile.setContentScore(finalScore);
                //TODO 考虑用chunkId还是fileId
                entityMap.put(exactFile.getChunkId(), exactFile);
            }

            // 处理向量匹配结果（已存在则更新分数，不存在则新增）
            for (DoFile vecFile : vectorList) {
                String chunkId = vecFile.getChunkId();
                if (entityMap.containsKey(chunkId)) {
                    // 已存在（精确+向量都命中）：累加向量权重分数
                    DoFile exist = entityMap.get(chunkId);
                    float newScore = exist.getContentScore() + VECTOR_WEIGHT * vecFile.getContentScore();
                    exist.setContentScore(newScore);
                } else {
                    // 不存在：仅向量分数
                    float finalScore = VECTOR_WEIGHT * vecFile.getContentScore();
                    vecFile.setContentScore(finalScore);
                    entityMap.put(chunkId, vecFile);
                }
            }

            // 4. 排序：按最终得分降序
            List<DoFile> files = new ArrayList<>(entityMap.values());
            files.sort((e1, e2) -> Float.compare(e2.getContentScore(), e1.getContentScore()));

            // 5. 截断到 topK
            if (files.size() > topK) {
                files = files.subList(0, topK);
            }
            log.info("[{}]表对内容[{}]混合搜索完成，结果数:{}", COLLECTION_NAME, content, files.size());
            return files;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对文件内容[{}]混合检索失败", COLLECTION_NAME, content);
            return Collections.emptyList();
        }
    }

}
