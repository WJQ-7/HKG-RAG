package com.bistu.hkgrag.dao.milvus.service;

import com.bistu.hkgrag.dao.milvus.model.DoCluster;

import com.bistu.hkgrag.dao.utils.ScoreNormalizeUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
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

@Service
@Slf4j
public class ClusterService {
    //定义file表
    private static final String COLLECTION_NAME = "cluster";
    //定义向量维度
    private static final int VECTOR_DIM = 2560;
    private final MilvusClientV2 client;
    private final EmbeddingModel embeddingModel;
    // 精确匹配权重
    @Value("${HKG-RAG.retrieval.EXACT-WEIGHT}")
    float EXACT_WEIGHT;
    @Value("${HKG-RAG.retrieval.BM25-WEIGHT}")
    float BM25_WEIGHT;
    // 向量匹配权重
    @Value("${HKG-RAG.retrieval.VECTOR_WEIGHT}")
    float VECTOR_WEIGHT;

    public ClusterService(MilvusClientV2 client,
                          @Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 判断表是否存在
     *
     * @return 返回是否存在表
     */
    public boolean hasCollection() {
        return client.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build());
    }

    /**
     * 创建cluster表
     *
     * @return 是否建表成功
     */
    public boolean createClusterCollection() {
        try {
            if (hasCollection()) {
                log.info("[{}]表已经存在", COLLECTION_NAME);
                return true;
            }
            CreateCollectionReq.CollectionSchema schema =
                    CreateCollectionReq.CollectionSchema.builder()
                            .build();

            //实体ID clusterId
            schema.addField(AddFieldReq.builder()
                    .fieldName("clusterId")
                    .dataType(DataType.VarChar)
                    .isPrimaryKey(true)
                    .maxLength(64)
                    .build());
            //实体名 clusterName
            schema.addField(AddFieldReq.builder()
                    .fieldName("clusterName")
                    .dataType(DataType.VarChar)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .maxLength(512).
                    build());
            //描述 description
            schema.addField(AddFieldReq.builder()
                    .fieldName("description")
                    .dataType(DataType.VarChar)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .maxLength(1024).
                    build());
            //实体名向量 clusterNameVector
            schema.addField(AddFieldReq.builder()
                    .fieldName("clusterNameVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //描述向量 descriptionVector
            schema.addField(AddFieldReq.builder()
                    .fieldName("descriptionVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //实体名稀疏向量 clusterNameSparse
            schema.addField(AddFieldReq.builder()
                    .fieldName("clusterNameSparse")
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("clusterName_bm25")
                    .inputFieldNames(Collections.singletonList("clusterName"))
                    .outputFieldNames(Collections.singletonList("clusterNameSparse"))
                    .build());
            //描述稀疏向量 descriptionSparse
            schema.addField(AddFieldReq.builder()
                    .fieldName("descriptionSparse")
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("description_bm25")
                    .inputFieldNames(Collections.singletonList("description"))
                    .outputFieldNames(Collections.singletonList("descriptionSparse"))
                    .build());

            List<IndexParam> indexParams = new ArrayList<>();
            indexParams.add(IndexParam.builder()
                    .fieldName("clusterNameVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("descriptionVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("clusterNameSparse")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("descriptionSparse")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX) // 稀疏倒排索引
                    .metricType(IndexParam.MetricType.BM25)                // 搭配 BM25 度量
                    .build());

            //构建createCollection请求
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .collectionSchema(schema)
                    .indexParams(indexParams)
                    .build();
            //用client发送创建collection的请求
            client.createCollection(req);
            log.info("[{}]表构建完成", COLLECTION_NAME);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表创建失败", COLLECTION_NAME);
            return false;
        }
    }

    /**
     * 判断Id是否已经存在
     *
     * @param clusterId 待查询的聚类实体Id
     * @return 是否已经存在
     */
    public boolean isClusterIdExist(String clusterId) {
        if (clusterId == null) {
            log.info("实体Id为空");
            return false;
        }
        QueryReq queryReq = QueryReq.builder()
                .collectionName(COLLECTION_NAME)
                .ids(Collections.singletonList(clusterId))
                .outputFields(Collections.singletonList("clusterId"))
                .limit(1)
                .build();
        QueryResp queryResp = client.query(queryReq);
        return !queryResp.getQueryResults().isEmpty();
    }


    /**
     * 插入单条实体
     *
     * @param cluster 待插入实体
     * @return 是否插入成功
     */
    public Boolean insertEntity(DoCluster cluster) {
        if (cluster.getClusterId() == null) {
            log.info("实体ID为空");
            return false;
        }
        if (isClusterIdExist(cluster.getClusterId())) {
            log.info("插入实体的ID[{}]已经存在", cluster.getClusterId());
            return false;
        }
        try {
            JsonObject row = new JsonObject();
            row.addProperty("clusterId", cluster.getClusterId());
            row.addProperty("clusterName", cluster.getClusterName());
            row.addProperty("description", cluster.getDescription());
            row.add("entityNameVector", new Gson().toJsonTree(cluster.getClusterNameVector()));
            row.add("descriptionVector", new Gson().toJsonTree(cluster.getDescriptionVector()));
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
            log.error("实体[{}]插入失败", cluster.getClusterId());
            return false;
        }
    }

    /**
     * 批量插入实体数据
     *
     * @param clusters 待插入的实体列表
     * @return 是否插入成功
     */
    public Boolean insertClusters(List<DoCluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            log.info("批量插入实体列表为空");
            return false;
        }
        try {
            List<JsonObject> data = new ArrayList<>();

            for (DoCluster cluster : clusters) {
                JsonObject row = new JsonObject();
                row.addProperty("clusterId", cluster.getClusterId());
                row.addProperty("clusterName", cluster.getClusterName());
                row.addProperty("description", cluster.getDescription());
                row.add("clusterNameVector", new Gson().toJsonTree(cluster.getClusterNameVector()));
                row.add("descriptionVector", new Gson().toJsonTree(cluster.getDescriptionVector()));
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
     * 根据聚类实体名进行精确匹配
     *
     * @param clusterName
     * @param topK
     * @return
     */
    public List<DoCluster> exactSearchByClusterName(String clusterName, int topK) {
        if (topK < 1) {
            log.info("通过实体名进行精确检索的topK小于1");
            return Collections.emptyList();
        }
        if (clusterName == null || clusterName.isEmpty()) {
            log.info("待进行精确检索的内容为空");
            return Collections.emptyList();
        }
        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("clusterName == \"" + clusterName + "\"")
                    .limit(topK)
                    .outputFields(List.of("clusterId", "clusterName"))
                    .build();

            QueryResp resp = client.query(queryReq);
            List<QueryResp.QueryResult> exactResults = resp.getQueryResults();
            List<DoCluster> entities = new ArrayList<>();
            for (QueryResp.QueryResult queryResult : exactResults) {
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(queryResult.getEntity().get("clusterId").toString());
                cluster.setClusterName(queryResult.getEntity().get("clusterName").toString());
                cluster.setDescription(queryResult.getEntity().get("description").toString());
                cluster.setClusterNameScore(1.0f);
                entities.add(cluster);
            }
            return entities;
        } catch (Exception e) {
            log.error("[{}]表对聚类节点名精确匹配失败，待匹配的话题：{}", COLLECTION_NAME, clusterName);
            return Collections.emptyList();
        }
    }

    /**
     * 对聚类名称进行BM25匹配
     *
     * @param clusterName 待匹配的聚类名称
     * @param topK    前k个文档
     * @return 最相关的前k个文档
     */
    public List<DoCluster> bm25SearchByClusterName(String clusterName, int topK) {

        if (topK < 1 || clusterName == null || clusterName.isEmpty()) {
            return Collections.emptyList();
        }

        try {

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("clusterNameSparse")
                    .data(List.of(new EmbeddedText(clusterName)))
                    .topK(topK)
                    .outputFields(List.of("clusterId", "clusterName", "description"))
                    .build();
            SearchResp resp = client.search(searchReq);
            List<DoCluster> clusters = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(map.get("clusterId").toString());
                cluster.setClusterName(map.get("clusterName").toString());
                cluster.setDescription(map.get("description").toString());
                cluster.setDescriptionScore(score);
                clusters.add(cluster);
            }

            ScoreNormalizeUtil.logAndImprovedMinMax(clusters, DoCluster::getDescriptionScore, DoCluster::setDescriptionScore);
            log.info("[{}]表对聚类名称[{}]BM25搜索完成，结果数:{}", COLLECTION_NAME, clusterName, clusters.size());
            return clusters;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对聚类名称进行BM25检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 根据聚类节点名称进行像向量检索
     *
     * @param clusterName 待检索聚类检点节点名
     * @param topK
     * @return
     */
    public List<DoCluster> vectorSearchByClusterName(String clusterName, int topK) {
        if (topK < 1) {
            log.info("通过聚类节点名进行向量检索的topK小于1");
            return Collections.emptyList();
        }
        if (clusterName == null || clusterName.isEmpty()) {
            log.info("待进行聚类节点名向量检索的话题为空");
            return Collections.emptyList();
        }
        try {
            float[] vector_entityName = embeddingModel.embed(clusterName);
            List<Float> vector = IntStream.range(0, vector_entityName.length)
                    .mapToObj(i -> vector_entityName[i])
                    .collect(Collectors.toList());
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("clusterNameVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("clusterId", "clusterName", "description"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoCluster> clusters = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(map.get("clusterId").toString());
                cluster.setClusterName(map.get("clusterName").toString());
                cluster.setDescription(map.get("description").toString());
                cluster.setClusterNameScore(score);
                clusters.add(cluster);
            }
            return clusters;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表向量匹配失败，实体名：{}", COLLECTION_NAME, clusterName);
            return Collections.emptyList();
        }
    }


    /**
     * 对聚类节点名进行混合检索
     *
     * @param clusterName 待检索聚类节点名
     * @return topK个匹配的聚类节点列表
     */
    public List<DoCluster> hybridSearchClusterName(String clusterName, int topK) {
        if (clusterName == null) {
            log.info("检索聚类节点名为空");
            return Collections.emptyList();
        }
        try {

            //1.进行精确匹配
            List<DoCluster> bm25List = bm25SearchByClusterName(clusterName, 2 * topK);
            //2.进行向量检索
            List<DoCluster> vectorList = vectorSearchByClusterName(clusterName, 2 * topK);
            //3.收集结果并进行去重
            Map<String, DoCluster> entityMap = new HashMap<>();

            // 处理精确匹配结果
            for (DoCluster exactCluster : bm25List) {
                // 加权分数：a*精确分 + b*向量分（精确匹配默认向量分为0）
                float finalScore = BM25_WEIGHT * exactCluster.getClusterNameScore();
                exactCluster.setClusterNameScore(finalScore);
                entityMap.put(exactCluster.getClusterId(), exactCluster);
            }

            // 处理向量匹配结果（已存在则更新分数，不存在则新增）
            for (DoCluster vecCluster : vectorList) {
                String clusterId = vecCluster.getClusterId();
                if (entityMap.containsKey(clusterId)) {
                    // 已存在（精确+向量都命中）：累加向量权重分数
                    DoCluster exist = entityMap.get(clusterId);
                    float newScore = exist.getClusterNameScore() + VECTOR_WEIGHT * vecCluster.getClusterNameScore();
                    exist.setClusterNameScore(newScore);
                } else {
                    // 不存在：仅向量分数
                    float finalScore = VECTOR_WEIGHT * vecCluster.getClusterNameScore();
                    vecCluster.setClusterNameScore(finalScore);
                    entityMap.put(clusterId, vecCluster);
                }
            }

            // 4. 排序：按最终得分降序
            List<DoCluster> entities = new ArrayList<>(entityMap.values());
            entities.sort((e1, e2) -> Float.compare(e2.getClusterNameScore(), e1.getClusterNameScore()));

            // 5. 截断到 topK
            if (entities.size() > topK) {
                entities = entities.subList(0, topK);
            }

            log.info("[{}]表对聚类节点名[{}]混合搜索完成，结果数:{}", COLLECTION_NAME, clusterName, entities.size());
            return entities;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对聚类节点名[{}]混合检索失败", COLLECTION_NAME, clusterName);
            return Collections.emptyList();
        }
    }

    /**
     * 根据聚类节点描述进行精确匹配
     *
     * @param description 待匹配的相关
     * @param topK
     * @return
     */
    public List<DoCluster> exactSearchByClusterDescription(String description, int topK) {
        if (topK < 1) {
            log.info("通过话题根据聚类节点描述的精确检索的topK小于1");
            return Collections.emptyList();
        }
        if (description == null || description.isEmpty()) {
            log.info("待进精确检索的话题为空");
            return Collections.emptyList();
        }
        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("description == \"" + description + "\"")
                    .limit(topK)
                    .outputFields(List.of("clusterId", "clusterName", "description"))
                    .build();

            QueryResp resp = client.query(queryReq);
            List<QueryResp.QueryResult> exactResults = resp.getQueryResults();
            List<DoCluster> entities = new ArrayList<>();
            for (QueryResp.QueryResult queryResult : exactResults) {
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(queryResult.getEntity().get("clusterId").toString());
                cluster.setClusterName(queryResult.getEntity().get("clusterName").toString());
                cluster.setDescription(queryResult.getEntity().get("description").toString());
                cluster.setDescriptionScore(1.0f);
                entities.add(cluster);
            }
            return entities;
        } catch (Exception e) {
            log.error("[{}]表精确匹配失败，待匹配的话题：{}", COLLECTION_NAME, description);
            return Collections.emptyList();
        }
    }

    /**
     * 对聚类描述进行BM25匹配
     *
     * @param description 待匹配的描述
     * @param topK    前k个文档
     * @return 最相关的前k个文档
     */
    public List<DoCluster> bm25SearchByDescription(String description, int topK) {

        if (topK < 1 || description == null || description.isEmpty()) {
            return Collections.emptyList();
        }

        try {

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("descriptionVector")
                    .data(List.of(new EmbeddedText(description)))
                    .topK(topK)
                    .outputFields(List.of("clusterId", "clusterName", "description"))
                    .build();
            SearchResp resp = client.search(searchReq);

            List<DoCluster> clusters = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(map.get("clusterId").toString());
                cluster.setClusterName(map.get("clusterName").toString());
                cluster.setDescription(map.get("description").toString());
                cluster.setDescriptionScore(score);
                clusters.add(cluster);
            }

            ScoreNormalizeUtil.logAndImprovedMinMax(clusters, DoCluster::getDescriptionScore, DoCluster::setDescriptionScore);
            log.info("[{}]表对聚类描述[{}]BM25搜索完成，结果数:{}", COLLECTION_NAME, description, clusters.size());
            return clusters;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对聚类描述进行BM25检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 根据聚类节点描述进行向量检索
     *
     * @param topic 待检索的相关话题
     * @param topK
     * @return
     */
    public List<DoCluster> vectorSearchByClusterDescription(String topic, int topK) {
        if (topK < 1) {
            log.info("通过实体名进行向量检索的topK小于1");
            return Collections.emptyList();
        }
        if (topic == null || topic.isEmpty()) {
            log.info("待进行向量检索的内容为空");
            return Collections.emptyList();
        }
        try {
            float[] vector_topic = embeddingModel.embed(topic);
            List<Float> vector = IntStream.range(0, vector_topic.length)
                    .mapToObj(i -> vector_topic[i])
                    .collect(Collectors.toList());
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("descriptionVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("clusterId", "clusterName", "description"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoCluster> clusters = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(map.get("clusterId").toString());
                cluster.setClusterName(map.get("clusterName").toString());
                cluster.setDescription(map.get("description").toString());
                cluster.setDescriptionScore(score);
                clusters.add(cluster);
            }
            return clusters;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表向量匹配失败，实体名：{}", COLLECTION_NAME, topic);
            return Collections.emptyList();
        }
    }


    /**
     * 用户问题话题与聚类节点描述进行混合检索
     *
     * @param topic 待检索的相关话题
     * @return topK个匹配的聚类节点列表
     */
    public List<DoCluster> hybridSearchClusterDescription(String topic, int topK) {
        if (topic == null) {
            log.info("待检索的相关话题为空");
            return Collections.emptyList();
        }
        try {
            //1.进行精确匹配
            List<DoCluster> bm25List = bm25SearchByDescription(topic, 2 * topK);
            //2.进行向量检索
            List<DoCluster> vectorList = vectorSearchByClusterDescription(topic, 2 * topK);
            //3.收集结果并进行去重
            Map<String, DoCluster> clusterMap = new HashMap<>();

            // 处理精确匹配结果
            for (DoCluster exactCluster : bm25List) {
                // 加权分数：a*精确分 + b*向量分（精确匹配默认向量分为0）
                float finalScore = BM25_WEIGHT * exactCluster.getDescriptionScore();
                exactCluster.setDescriptionScore(finalScore);
                clusterMap.put(exactCluster.getClusterId(), exactCluster);
            }

            // 处理向量匹配结果（已存在则更新分数，不存在则新增）
            for (DoCluster vecCluster : vectorList) {
                String clusterId = vecCluster.getClusterId();
                if (clusterMap.containsKey(clusterId)) {
                    // 已存在（精确+向量都命中）：累加向量权重分数
                    DoCluster exist = clusterMap.get(clusterId);
                    float newScore = exist.getDescriptionScore() + VECTOR_WEIGHT * vecCluster.getDescriptionScore();
                    exist.setDescriptionScore(newScore);
                } else {
                    // 不存在：仅向量分数
                    float finalScore = VECTOR_WEIGHT * vecCluster.getDescriptionScore();
                    vecCluster.setDescriptionScore(finalScore);
                    clusterMap.put(clusterId, vecCluster);
                }
            }

            // 4. 排序：按最终得分降序
            List<DoCluster> clusters = new ArrayList<>(clusterMap.values());
            clusters.sort((e1, e2) -> Float.compare(e2.getDescriptionScore(), e1.getDescriptionScore()));

            // 5. 截断到 topK
            if (clusters.size() > topK) {
                clusters = clusters.subList(0, topK);
            }

            log.info("[{}]表对相关话题[{}]混合搜索完成，结果数:{}", COLLECTION_NAME, topic, clusters.size());
            return clusters;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对相关话题[{}]混合检索失败", COLLECTION_NAME, topic);
            return Collections.emptyList();
        }
    }

    /**
     * 对问题中的话题进行聚类节点名和聚类节点描述的混合检索
     * @param topic 待检索的相关话题
     * @param topK
     * @return
     */
    public List<DoCluster> hybridSearchByNameAndDescription(String topic, int topK) {
        try{
            float[] vectorTopic = embeddingModel.embed(topic);
            List<AnnSearchReq> searchRequests = new ArrayList<>();
            searchRequests.add(AnnSearchReq.builder()
                    .vectorFieldName("clusterNameVector")
                    .vectors(Collections.singletonList(new FloatVec(vectorTopic)))
                    .limit(2 * topK)
                    .build());
            searchRequests.add(AnnSearchReq.builder()
                    .vectorFieldName("descriptionVector")
                    .vectors(Collections.singletonList(new FloatVec(vectorTopic)))
                    .limit(2 * topK)
                    .build());

            HybridSearchReq hybridSearchReq = HybridSearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .searchRequests(searchRequests)
                    .outFields(List.of("clusterId", "clusterName", "description"))
                    .ranker(new RRFRanker(4 * topK))
                    .topK(topK)
                    .build();
            SearchResp searchResp = client.hybridSearch(hybridSearchReq);

            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
            List<SearchResp.SearchResult> results = searchResults.get(0);
            List<DoCluster> clusters = new ArrayList<>();
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> map = result.getEntity();
                Float score = result.getScore();
                DoCluster cluster = new DoCluster();
                cluster.setClusterId(map.get("clusterId").toString());
                cluster.setClusterName(map.get("clusterName").toString());
                cluster.setDescription(map.get("description").toString());
                cluster.setDescriptionScore(score);
                clusters.add(cluster);

            }
            return clusters;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对相关话题[{}]进行聚类节点名和聚类节点描述的混合检索失败", COLLECTION_NAME, topic);
            return Collections.emptyList();
        }
    }

    public List<DoCluster> batchHybridSearchByNameAndDescription(List<String> topics, int topK) {
        List<DoCluster> clusterList = new ArrayList<>();
        for(String topic : topics){
            List<DoCluster> clusters=hybridSearchByNameAndDescription(topic, topK);
            clusterList.addAll(clusters);
        }

        return clusterList.stream()
                .sorted((c1, c2) -> Double.compare(c2.getDescriptionScore(), c1.getDescriptionScore()))
                .limit(topK)
                .toList();

    }
}
