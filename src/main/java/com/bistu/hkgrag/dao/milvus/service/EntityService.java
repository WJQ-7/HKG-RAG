package com.bistu.hkgrag.dao.milvus.service;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.utils.ScoreNormalizeUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema;
import io.milvus.v2.client.MilvusClientV2;
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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Entity数据库(Milvus)表的操作类
 *
 * @author wangjingqi
 */
@Slf4j
@Service
public class EntityService {
    //定义file表
    @Value("${HKG-RAG.milvus.Entity-Collection}")
    private String COLLECTION_NAME = "entity";
    //定义向量维度
    private static final int VECTOR_DIM = 2560;
    private final MilvusClientV2 client;
    private final EmbeddingModel embeddingModel;

    // 精确匹配权重
    @Value("${HKG-RAG.retrieval.EXACT-WEIGHT}")
    float EXACT_WEIGHT;
    //BM25匹配权重
    @Value("${HKG-RAG.retrieval.BM25-WEIGHT}")
    float BM25_WEIGHT;
    // 向量匹配权重
    @Value("${HKG-RAG.retrieval.VECTOR_WEIGHT}")
    float VECTOR_WEIGHT;


    public EntityService(MilvusClientV2 client,
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
     * 创建内容实体表
     *
     * @return 是否建表成功
     */
    public boolean createEntityCollection() {
        try {
            if (hasCollection()) {
                log.info("[{}]表已经存在", COLLECTION_NAME);
                return true;
            }
            CreateCollectionReq.CollectionSchema schema =
                    CreateCollectionReq.CollectionSchema.builder()
                            .build();


            //实体ID entityId
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityId")
                    .dataType(DataType.VarChar)
                    .isPrimaryKey(true)
                    .maxLength(64)
                    .build());

            //实体名 entityName
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityName")
                    .dataType(DataType.VarChar)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .maxLength(1024).
                    build());
            //实体名向量 entityNameVector
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityNameVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //实体名 稀疏向量 entityNameSparse
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityNameSparse")
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("entityName_bm25")
                    .inputFieldNames(Collections.singletonList("entityName"))
                    .outputFieldNames(Collections.singletonList("entityNameSparse"))
                    .build());

            //实体描述 entityDescription
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityDescription")
                    .dataType(DataType.VarChar)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .maxLength(8192)
                    .build());
            //实体描述向量 entityDescriptionVector
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityDescriptionVector")
                    .dataType(DataType.FloatVector)
                    .dimension(VECTOR_DIM)
                    .build());
            //实体描述 稀疏向量 entityDescriptionSparse
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityDescriptionSparse")
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("entityDescription_bm25")
                    .inputFieldNames(Collections.singletonList("entityDescription"))
                    .outputFieldNames(Collections.singletonList("entityDescriptionSparse"))
                    .build());

            //实体信息 entityInfo（仅存储文本，不做向量索引，Milvus限制最多4个向量字段）
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityInfo")
                    .dataType(DataType.VarChar)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .maxLength(4096)
                    .build());

            //实体类型 entityType
            schema.addField(AddFieldReq.builder()
                    .fieldName("entityType")
                    .dataType(DataType.VarChar)
                    .maxLength(256)
                    .build());

            List<IndexParam> indexParams = new ArrayList<>();
            // entityName 索引
            indexParams.add(IndexParam.builder()
                    .fieldName("entityNameVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("entityNameSparse")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());
            // entityDescription 索引
            indexParams.add(IndexParam.builder()
                    .fieldName("entityDescriptionVector")
                    .metricType(IndexParam.MetricType.COSINE)
                    .build());
            indexParams.add(IndexParam.builder()
                    .fieldName("entityDescriptionSparse")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
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
     * 判断实体Id是否已经存在
     *
     * @param entityId 待查询的实体Id
     * @return 是否已经存在
     */
    public boolean isEntityIdExist(String entityId) {
        if (entityId == null) {
            log.info("实体Id为空");
            return false;
        }
        QueryReq queryReq = QueryReq.builder()
                .collectionName(COLLECTION_NAME)
                .ids(Collections.singletonList(entityId))
                .outputFields(Collections.singletonList("entityId"))
                .limit(1)
                .build();
        QueryResp queryResp = client.query(queryReq);
        return !queryResp.getQueryResults().isEmpty();
    }

    /**
     * 插入单条实体
     *
     * @param entity 待插入实体
     * @return 是否插入成功
     */
    public Boolean insertEntity(DoEntity entity) {
        if (entity.getEntityId() == null) {
            log.info("实体ID为空");
            return false;
        }
        if (isEntityIdExist(entity.getEntityId())) {
            log.info("插入实体的ID[{}]已经存在", entity.getEntityId());
            return false;
        }
        try {
            JsonObject row = new JsonObject();
            row.addProperty("entityId", entity.getEntityId());
            row.addProperty("entityName", entity.getEntityName());
            row.add("entityNameVector", new Gson().toJsonTree(entity.getEntityNameVector()));
            row.addProperty("entityDescription", entity.getEntityDescription() != null ? entity.getEntityDescription() : "");
            if (entity.getEntityDescriptionVector() != null) {
                row.add("entityDescriptionVector", new Gson().toJsonTree(entity.getEntityDescriptionVector()));
            }
            // entityInfo 有 analyzer，必须提供值，null 时填空字符串
            row.addProperty("entityInfo", entity.getEntityInfo() != null ? entity.getEntityInfo() : "");
            row.addProperty("entityType", entity.getEntityType() != null ? entity.getEntityType() : "");
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
            log.error("实体[{}]插入失败", entity.getEntityId());
            return false;
        }
    }

    /**
     * 批量插入实体数据
     *
     * @param entities 待插入的实体列表
     * @return 是否插入成功
     */
    public Boolean insertEntities(List<DoEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            log.info("批量插入实体列表为空");
            return false;
        }
        try {
            List<JsonObject> data = new ArrayList<>();

            for (DoEntity entity : entities) {
                JsonObject row = new JsonObject();
                row.addProperty("entityId", entity.getEntityId());
                row.addProperty("entityName", entity.getEntityName());
                row.add("entityNameVector", new Gson().toJsonTree(entity.getEntityNameVector()));
                row.addProperty("entityDescription", entity.getEntityDescription() != null ? entity.getEntityDescription() : "");
                if (entity.getEntityDescriptionVector() != null) {
                    row.add("entityDescriptionVector", new Gson().toJsonTree(entity.getEntityDescriptionVector()));
                }
                // entityInfo 有 analyzer，必须提供值，null 时填空字符串
                row.addProperty("entityInfo", entity.getEntityInfo() != null ? entity.getEntityInfo() : "");
                row.addProperty("entityType", entity.getEntityType() != null ? entity.getEntityType() : "");
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
     * 根据实体名进行精确匹配
     * @param entityName
     * @param topK
     * @return
     */
    public List<DoEntity> exactSearchByEntityName(String entityName,int topK) {
        if (topK < 1) {
            log.info("通过实体名进行精确检索的topK小于1");
            return Collections.emptyList();
        }
        if (entityName == null || entityName.isEmpty()) {
            log.info("待进行精确检索的内容为空");
            return Collections.emptyList();
        }
        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("entityName == \"" + entityName + "\"")
                    .limit(topK)
                    .outputFields(List.of("entityId", "entityName", "entityType", "entityDescription", "entityInfo"))
                    .build();

            QueryResp resp = client.query(queryReq);
            List<QueryResp.QueryResult> exactResults = resp.getQueryResults();
            List<DoEntity> entities = new ArrayList<>();
            for (QueryResp.QueryResult queryResult : exactResults) {
                Map<String, Object> fields = queryResult.getEntity();
                DoEntity entity = new DoEntity();
                entity.setEntityId(getStringField(fields, "entityId"));
                entity.setEntityName(getStringField(fields, "entityName"));
                entity.setEntityType(getStringField(fields, "entityType"));
                entity.setEntityDescription(getStringField(fields, "entityDescription"));
                entity.setEntityInfo(getStringField(fields, "entityInfo"));
                entity.setNameScore(1.0f);
                entities.add(entity);
            }
            return entities;
        } catch (Exception e) {
            log.error("[{}]表精确匹配失败，实体名：{}", COLLECTION_NAME, entityName);
            return Collections.emptyList();
        }
    }

    /**
     * 对实体名称进行BM25匹配
     *
     * @param entityName 待匹配的文本
     * @param topK    前k个实体
     * @return 最相关的前k个实体
     */
    public List<DoEntity> bm25SearchByEntityName(String entityName, int topK) {

        if (topK < 1 || entityName == null || entityName.isEmpty()) {
            return Collections.emptyList();
        }

        try {

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("entityNameSparse")
                    .data(List.of(new EmbeddedText(entityName)))
                    .topK(topK)
                    .outputFields(List.of("entityId", "entityName", "entityType", "entityDescription", "entityInfo"))
                    .build();
            SearchResp resp = client.search(searchReq);

            List<DoEntity> entities = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> fields = result.getEntity();
                Float score = result.getScore();
                DoEntity entity = new DoEntity();
                entity.setEntityId(getStringField(fields, "entityId"));
                entity.setEntityName(getStringField(fields, "entityName"));
                entity.setEntityType(getStringField(fields, "entityType"));
                entity.setEntityDescription(getStringField(fields, "entityDescription"));
                entity.setEntityInfo(getStringField(fields, "entityInfo"));
                entity.setNameScore(score);
                entities.add(entity);
            }

            ScoreNormalizeUtil.logAndImprovedMinMax(entities, DoEntity::getNameScore, DoEntity::setNameScore);
            log.info("[{}]表对实体名[{}]BM25搜索完成，结果数:{}", COLLECTION_NAME, entityName, entities.size());
            return entities;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对实体名称进行BM25检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 根据实体名进行像向量检索
     * @param entityName 待检索实体名
     * @param topK
     * @return
     */
    public List<DoEntity> vectorSearchByEntityName(String entityName,int topK) {
        if (topK < 1) {
            log.info("通过实体名进行向量检索的topK小于1");
            return Collections.emptyList();
        }
        if (entityName == null || entityName.isEmpty()) {
            log.info("待进行向量检索的内容为空");
            return Collections.emptyList();
        }
        try{
            float[]  vector_entityName=embeddingModel.embed(entityName);
            List<Float> vector = IntStream.range(0, vector_entityName.length)
                    .mapToObj(i -> vector_entityName[i])
                    .collect(Collectors.toList());
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("entityNameVector")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("entityId", "entityName", "entityType", "entityDescription", "entityInfo"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoEntity> entities = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> fields = result.getEntity();
                Float score = result.getScore();
                DoEntity entity = new DoEntity();
                entity.setEntityId(getStringField(fields, "entityId"));
                entity.setEntityName(getStringField(fields, "entityName"));
                entity.setEntityType(getStringField(fields, "entityType"));
                entity.setEntityDescription(getStringField(fields, "entityDescription"));
                entity.setEntityInfo(getStringField(fields, "entityInfo"));
                entity.setNameScore(score);
                entities.add(entity);
            }
            return entities;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表向量匹配失败，实体名：{}", COLLECTION_NAME, entityName);
            return Collections.emptyList();
        }
    }


    // ==================== entityDescription 检索方法 ====================

    /**
     * 对实体描述进行BM25匹配
     *
     * @param entityDescription 待匹配的文本
     * @param topK              前k个实体
     * @return 最相关的前k个实体
     */
    public List<DoEntity> bm25SearchByEntityDescription(String entityDescription, int topK) {
        if (topK < 1 || entityDescription == null || entityDescription.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("entityDescriptionSparse")
                    .data(List.of(new EmbeddedText(entityDescription)))
                    .topK(topK)
                    .outputFields(List.of("entityId", "entityName", "entityType", "entityDescription", "entityInfo"))
                    .build();
            SearchResp resp = client.search(searchReq);

            List<DoEntity> entities = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> fields = result.getEntity();
                Float score = result.getScore();
                DoEntity entity = new DoEntity();
                entity.setEntityId(getStringField(fields, "entityId"));
                entity.setEntityName(getStringField(fields, "entityName"));
                entity.setEntityType(getStringField(fields, "entityType"));
                entity.setEntityDescription(getStringField(fields, "entityDescription"));
                entity.setEntityInfo(getStringField(fields, "entityInfo"));
                entity.setDescriptionScore(score);
                entities.add(entity);
            }

            ScoreNormalizeUtil.logAndImprovedMinMax(entities, DoEntity::getDescriptionScore, DoEntity::setDescriptionScore);
            log.info("[{}]表对实体描述BM25搜索完成，结果数:{}", COLLECTION_NAME, entities.size());
            return entities;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对实体描述进行BM25检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 对实体描述进行向量检索
     *
     * @param entityDescription 待检索文本
     * @param topK              前k个实体
     * @return 最相关的前k个实体
     */
    public List<DoEntity> vectorSearchByEntityDescription(String entityDescription, int topK) {
        if (topK < 1 || entityDescription == null || entityDescription.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            float[] vector = embeddingModel.embed(entityDescription);
            List<Float> vectorList = IntStream.range(0, vector.length)
                    .mapToObj(i -> vector[i])
                    .collect(Collectors.toList());
            SearchReq searchReq = SearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .annsField("entityDescriptionVector")
                    .data(Collections.singletonList(new FloatVec(vectorList)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("entityId", "entityName", "entityType", "entityDescription", "entityInfo"))
                    .build();

            SearchResp resp = client.search(searchReq);
            List<DoEntity> entities = new ArrayList<>();
            List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
            for (SearchResp.SearchResult result : results) {
                Map<String, Object> fields = result.getEntity();
                Float score = result.getScore();
                DoEntity entity = new DoEntity();
                entity.setEntityId(getStringField(fields, "entityId"));
                entity.setEntityName(getStringField(fields, "entityName"));
                entity.setEntityType(getStringField(fields, "entityType"));
                entity.setEntityDescription(getStringField(fields, "entityDescription"));
                entity.setEntityInfo(getStringField(fields, "entityInfo"));
                entity.setDescriptionScore(score);
                entities.add(entity);
            }
            return entities;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对实体描述向量检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 对实体描述进行混合检索（BM25 + 向量），融合后按 score 排序
     *
     * @param queryText 待检索的描述文本
     * @param topK      前k个实体
     * @return 融合排序后的实体列表
     */
    public List<DoEntity> hybridSearchByEntityDescription(String queryText, int topK) {
        if (queryText == null || queryText.isEmpty()) {
            log.info("检索描述文本为空");
            return Collections.emptyList();
        }
        try {
            // 1. BM25检索
            List<DoEntity> bm25List = bm25SearchByEntityDescription(queryText, 2 * topK);
            // 2. 向量检索
            List<DoEntity> vectorList = vectorSearchByEntityDescription(queryText, 2 * topK);

            // 3. 去重融合
            Map<String, DoEntity> entityMap = new HashMap<>();

            for (DoEntity bm25Entity : bm25List) {
                float finalScore = BM25_WEIGHT * bm25Entity.getDescriptionScore();
                bm25Entity.setScore(finalScore);
                entityMap.put(bm25Entity.getEntityId(), bm25Entity);
            }

            for (DoEntity vecEntity : vectorList) {
                String entityId = vecEntity.getEntityId();
                if (entityMap.containsKey(entityId)) {
                    DoEntity exist = entityMap.get(entityId);
                    float newScore = exist.getScore() + VECTOR_WEIGHT * vecEntity.getDescriptionScore();
                    exist.setScore(newScore);
                } else {
                    float finalScore = VECTOR_WEIGHT * vecEntity.getDescriptionScore();
                    vecEntity.setScore(finalScore);
                    entityMap.put(entityId, vecEntity);
                }
            }

            // 4. 排序截断
            List<DoEntity> entities = new ArrayList<>(entityMap.values());
            entities.sort((e1, e2) -> Float.compare(e2.getScore(), e1.getScore()));
            if (entities.size() > topK) {
                entities = entities.subList(0, topK);
            }

            log.info("[{}]表对实体描述混合搜索完成，结果数:{}", COLLECTION_NAME, entities.size());
            return entities;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对实体描述混合检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 对实体名进行混合检索
     *
     * @param entityName 待检索实体名
     * @return topK个匹配的实体列表
     */
    @Tool(description = "根据一个实体的名称进行混合检索（向量+精确匹配），返回匹配的实体列表")
    public List<DoEntity> hybridSearchEntity(@ToolParam(description = "待检索的实体名称，例如：张三、北京、苹果公司") String entityName,int topK) {
        if (entityName == null) {
            log.info("检索实体名为空");
            return Collections.emptyList();
        }
        try {

            //1.进行精确匹配
            List<DoEntity> bm25List=bm25SearchByEntityName(entityName, 2*topK);
            //2.进行向量检索
            List<DoEntity> vectorList=vectorSearchByEntityName(entityName, 2*topK);
            //3.收集结果并进行去重
            Map<String, DoEntity> entityMap = new HashMap<>();

            // 处理精确匹配结果
            for (DoEntity exactEntity : bm25List) {
                // 加权分数：a*精确分 + b*向量分（精确匹配默认向量分为0）
                float finalScore = BM25_WEIGHT * exactEntity.getNameScore();
                exactEntity.setScore(finalScore);
                entityMap.put(exactEntity.getEntityId(), exactEntity);
            }

            // 处理向量匹配结果（已存在则更新分数，不存在则新增）
            for (DoEntity vecEntity : vectorList) {
                String entityId = vecEntity.getEntityId();
                if (entityMap.containsKey(entityId)) {
                    // 已存在（精确+向量都命中）：累加向量权重分数
                    DoEntity exist = entityMap.get(entityId);
                    float newScore = exist.getScore() + VECTOR_WEIGHT * vecEntity.getNameScore();
                    exist.setScore(newScore);
                } else {
                    // 不存在：仅向量分数
                    float finalScore = VECTOR_WEIGHT * vecEntity.getNameScore();
                    vecEntity.setScore(finalScore);
                    entityMap.put(entityId, vecEntity);
                }
            }

            // 4. 排序：按最终得分降序
            List<DoEntity> entities = new ArrayList<>(entityMap.values());
            entities.sort((e1, e2) -> Float.compare(e2.getScore(), e1.getScore()));

            // 5. 截断到 topK
            if (entities.size() > topK) {
                entities = entities.subList(0, topK);
            }

            log.info("[{}]表对实体名[{}]混合搜索完成，结果数:{}", COLLECTION_NAME, entityName, entities.size());
            return entities;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表对实体名[{}]混合检索失败", COLLECTION_NAME, entityName);
            return Collections.emptyList();
        }
    }

    @Tool(description = "根据实体名称列表批量进行混合检索（向量+精确匹配），返回所有匹配的实体列表")
    public List<DoEntity> hybridSearchEntityList(@ToolParam(description = "待检索的实体名称列表，例如：[张三、北京、苹果公司]") List<String> entityNameList, int topK) {
        // 1. 空值判断
        if (CollectionUtils.isEmpty(entityNameList)) {
            log.info("批量检索实体名称列表为空");
            return Collections.emptyList();
        }
        if (topK < 1) {
            log.info("topK小于1");
            return Collections.emptyList();
        }
        List<DoEntity> allResultEntities = new ArrayList<>();
        try {
            // 2. 遍历每个实体名，分别执行混合搜索
            for (String entityName : entityNameList) {
                allResultEntities.addAll(hybridSearchEntity(entityName, topK));
            }
            log.info("[{}]表批量混合搜索完成，总结果数:{}", COLLECTION_NAME, allResultEntities.size());
            return allResultEntities;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("[{}]表批量混合检索失败", COLLECTION_NAME);
            return Collections.emptyList();
        }
    }

    /**
     * 安全获取 Map 中的字符串字段，null 时返回空字符串
     */
    private String getStringField(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value != null ? value.toString() : "";
    }
}

