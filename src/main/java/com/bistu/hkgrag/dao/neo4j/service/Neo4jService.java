package com.bistu.hkgrag.dao.neo4j.service;

import com.bistu.hkgrag.dao.neo4j.model.Entity;
import com.bistu.hkgrag.dao.neo4j.model.Relation;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j图数据库的操作类
 *
 * @author wangjingqi
 */
@Slf4j
@Service
public class Neo4jService {
    private final Neo4jClient neo4jClient;

    public Neo4jService(@Autowired Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 根据节点id查找节点
     *
     * @param id 节点id
     * @return 节点所有属性信息
     */
    public Map<String, Object> findNodeById(String id) {
        String cypher = """
                MATCH (n {entityId:$id})
                RETURN properties(n) AS NODE
                """;
        return neo4jClient.query(cypher)
                .bind(id).to("id")
                .fetch()
                .one()
                .map(res -> (Map<String, Object>) res.values().iterator().next())
                .orElse(null);
    }

    /**
     * 根据实体ID列表和指定标签类型检索节点及其属性（含 labels 字段）
     * 相比 findNodeByEntitiesId，直接在 Cypher 层面按标签过滤
     *
     * @param ids   待检索的实体ID列表
     * @param label 节点标签，如 "内容实体"、"文档"、"主题聚类节点"
     * @return 指定标签的节点列表，每个 Map 包含节点所有属性 + "labels" 字段
     */
    public List<Map<String, Object>> findNodeByEntitiesIdAndLabel(List<String> ids, String label) {
        if (ids == null || ids.isEmpty() || label == null || label.isEmpty()) {
            return new ArrayList<>();
        }

        String cypher = String.format("""
                MATCH (n:`%s`)
                WHERE n.entityId IN $ids
                RETURN DISTINCT n, labels(n) AS labels
                """, label);

        return neo4jClient.query(cypher)
                .bind(ids).to("ids")
                .fetch()
                .all()
                .stream()
                .map(record -> {
                    Node node = (Node) record.get("n");
                    Map<String, Object> resultMap = new HashMap<>();
                    node.asMap().forEach((key, value) ->
                            resultMap.put(key, value == null ? "" : value)
                    );
                    resultMap.put("labels", record.get("labels"));
                    return resultMap;
                })
                .toList();
    }

    /**
     * 根据节点类型(label)获取文档
     * @param label 文档类型
     * @return
     */
    public List<Map<String, String>> findNodeByLabel(String label) {
        // 1. 简单校验，防止Cypher注入
        if(label == null || label.isEmpty()) {
            return new ArrayList<>();
        }
        if (!label.matches("[\\p{L}0-9_]+")) {
            throw new IllegalArgumentException("非法label");
        }

        // 2. 动态拼接label
        String cypher = String.format("""
            MATCH (n:`%s`)
            RETURN properties(n) AS NODE
            """, label);

        // 3. 查询
        return neo4jClient.query(cypher)
                .fetch()
                .all()
                .stream()
                .map(res -> (Map<String, String>) res.get("NODE"))
                .toList();

    }

    /**
     * 根据id列表获取图谱内对应节点及属性
     * @param id 待检索id列表
     * @return
     */
    public List<Map<String, Object>> findNodeByEntitiesId(List<String> id) {
        // 空值安全处理
        if (id == null || id.isEmpty()) {
            return new ArrayList<>();
        }

        // Cypher：根据节点自身的 entityId 属性查询
        String cypher = """
                MATCH (n)
                WHERE n.entityId IN $ids
                RETURN DISTINCT n
                """;

        // 执行查询并返回标准格式
        return neo4jClient.query(cypher)
                .bind(id).to("ids")
                .fetch()
                .all()
                .stream()
                .map(record -> {
                    Node node = (Node) record.get("n");
                    // 这里改成 Map<String, Object>
                    Map<String, Object> resultMap = new HashMap<>();
                    node.asMap().forEach((key, value) ->
                            resultMap.put(key, value == null ? "" : value)
                    );
                    return resultMap;
                })
                .toList();
    }


    /**
     * 根据节点name查找节点
     *
     * @param name 节点name
     * @return 节点所有属性信息
     */
    @Tool(description = "根据节点名称name，在知识图谱Neo4j中精确查询节点，返回节点的全部属性信息")
    public List<Map<String, Object>> findAllNodeByName(@ToolParam(description = "要查询的节点名称，例如：张三、北京大学、人工智能") String name) {
        String cypher = """
                MATCH (n {name:$name})
                RETURN properties(n) AS NODE
                """;
        return neo4jClient.query(cypher)
                .bind(name).to("name")
                .fetch()
                .all()
                .stream()
                .map(record -> (Map<String, Object>) record.get("NODE"))
                .toList();

    }

    /**
     * 根据节点id找到所有该节点的邻居节点及关系
     *
     * @param id 待查找的节点id
     * @return 所有与该节点链接的节点与关系
     */
    public List<Map<String, Object>> findRelationshipById(String id) {
        String cypher = """
                MATCH (n {entityId: $id})-[r]-(m)
                RETURN properties(n) AS start, properties(r) AS relationId,type(r) As relationType, properties(m) As end
                """;
        return neo4jClient.query(cypher)
                .bind(id).to("id")
                .fetch()
                .all()
                .stream()
                .toList();

    }

    /**
     * 根据节点id找属性为“文档”的相邻节点
     * 主要用于根据内容实体的id找到它所属的文档  根据主题聚类节点id找所属文档
     *
     * @param id 待查找节点id
     * @return 节点关系列表
     */
    public List<Map<String, Object>> findFileById(String id) {
        String cypher = """
                MATCH (n {entityId: $id})-[r]-(m:文档) 
                RETURN properties(n) AS start, 
                       properties(r) AS relationId,
                       type(r) As relationType,
                       properties(m) As end
                """;
        return neo4jClient.query(cypher)
                .bind(id).to("id")
                .fetch()
                .all()
                .stream()
                .toList();
    }

    /**
     * 根据实体ID列表获取三元组（主体-关系-客体），优先返回"内容实体"邻居，限制返回条数。
     * 每条三元组包含主体名、关系类型、客体名、客体标签，便于 LLM 直接理解图谱结构。
     *
     * 去重策略：同一对 (sourceId, relationType, targetId) 只保留一条。
     * 排序策略：邻居标签包含"内容实体"的排在前面（1），其他排后面（0），
     * 同组内按邻居出度降序排列，保证优先返回热门的内容实体。
     *
     * @param entityIds 实体ID列表
     * @param limit     最大返回条数
     * @return 三元组列表（去重，内容实体优先）
     */
    public List<Map<String, Object>> findTriplesByEntityIds(List<String> entityIds, int limit) {
        if (entityIds == null || entityIds.isEmpty()) {
            return new ArrayList<>();
        }
        String cypher = """
                MATCH (n)-[r]-(neighbor)
                WHERE n.entityId IN $ids
                WITH n.entityId AS sourceId,
                     n.entityName AS sourceName,
                     labels(n) AS sourceLabels,
                     type(r) AS relationType,
                     neighbor.entityId AS targetId,
                     neighbor.entityName AS targetName,
                     labels(neighbor) AS targetLabels,
                     CASE WHEN '内容实体' IN labels(neighbor) THEN 1 ELSE 0 END AS isContentEntity,
                     size([(neighbor)-[]-() | 1]) AS neighborDegree
                // 按 (sourceId, relationType, targetId) 分组去重，取最大出度
                RETURN sourceName, sourceLabels, sourceId,
                       relationType,
                       targetName, targetLabels, targetId,
                       max(neighborDegree) AS neighborDegree,
                       max(isContentEntity) AS isContentEntity
                ORDER BY isContentEntity DESC, neighborDegree DESC
                LIMIT $limit
                """;
        return neo4jClient.query(cypher)
                .bind(entityIds).to("ids")
                .bind(limit).to("limit")
                .fetch()
                .all()
                .stream()
                .map(record -> {
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("sourceName", record.getOrDefault("sourceName", "未知"));
                    resultMap.put("sourceLabels", record.getOrDefault("sourceLabels", ""));
                    resultMap.put("sourceId", record.getOrDefault("sourceId", ""));
                    resultMap.put("relationType", record.getOrDefault("relationType", ""));
                    resultMap.put("targetName", record.getOrDefault("targetName", "未知"));
                    resultMap.put("targetLabels", record.getOrDefault("targetLabels", ""));
                    resultMap.put("targetId", record.getOrDefault("targetId", ""));
                    resultMap.put("neighborDegree", record.getOrDefault("neighborDegree", 0));
                    return resultMap;
                })
                .toList();
    }

    /**
     * 获取节点的所有关系类型列表（去重）
     * 供智能体了解该节点有哪些关系可用来扩展，从而选择有意义的关系
     *
     * @param entityIds 实体ID列表
     * @return 关系类型列表及对应的邻居标签统计
     */
    public List<Map<String, Object>> getRelationTypesByEntitiesId(List<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return new ArrayList<>();
        }

        String cypher = """
                MATCH (n)-[r]-(neighbor)
                WHERE n.entityId IN $ids
                RETURN DISTINCT type(r) AS relationType,
                       collect(DISTINCT head(labels(neighbor))) AS neighborLabels,
                       count(DISTINCT neighbor) AS neighborCount
                LIMIT 100
                """;

        return neo4jClient.query(cypher)
                .bind(entityIds).to("ids")
                .fetch()
                .all()
                .stream()
                .map(record -> {
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("relationType", record.get("relationType"));
                    resultMap.put("neighborLabels", record.get("neighborLabels"));
                    resultMap.put("neighborCount", record.get("neighborCount"));
                    return resultMap;
                })
                .toList();
    }

    /**
     * 按指定关系类型查找邻居节点
     * 智能体可以从 getRelationTypesByEntitiesId 返回的关系类型中选择一个或多个，
     * 然后调用本方法进行定向扩展
     *
     * @param startEntityIds 起始实体ID列表
     * @param relationTypes  要筛选的关系类型列表（可选，为空则返回所有关系的邻居）
     * @return 邻居节点列表（含关系类型和邻居属性）
     */
    public List<Map<String, Object>> findNeighborsByEntitiesIdAndRelation(
            List<String> startEntityIds, List<String> relationTypes) {
        if (startEntityIds == null || startEntityIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 如果指定了关系类型，则按类型筛选；否则返回所有邻居
        String cypher;
        if (relationTypes != null && !relationTypes.isEmpty()) {
            cypher = """
                    MATCH (n)-[r]-(neighbor)
                    WHERE n.entityId IN $ids AND type(r) IN $relTypes
                    RETURN DISTINCT properties(n) AS sourceNode,
                           type(r) AS relationType,
                           properties(r) AS relationProps,
                           labels(neighbor) AS neighborLabels,
                           properties(neighbor) AS neighborNode
                    LIMIT 200
                    """;
        } else {
            cypher = """
                    MATCH (n)-[r]-(neighbor)
                    WHERE n.entityId IN $ids
                    RETURN DISTINCT properties(n) AS sourceNode,
                           type(r) AS relationType,
                           properties(r) AS relationProps,
                           labels(neighbor) AS neighborLabels,
                           properties(neighbor) AS neighborNode
                    LIMIT 200
                    """;
        }

        var query = neo4jClient.query(cypher).bind(startEntityIds).to("ids");
        if (relationTypes != null && !relationTypes.isEmpty()) {
            query = query.bind(relationTypes).to("relTypes");
        }

        return query.fetch()
                .all()
                .stream()
                .map(record -> {
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("sourceNode", record.get("sourceNode"));
                    resultMap.put("relationType", record.get("relationType"));
                    resultMap.put("relationProps", record.get("relationProps"));
                    resultMap.put("neighborLabels", record.get("neighborLabels"));
                    resultMap.put("neighborNode", record.get("neighborNode"));
                    return resultMap;
                })
                .toList();
    }

    /**
     * 根据实体id查找所有与之相连得文件
     * @param id
     * @return
     */
    public List<Map<String, String>> findFileByEntitiesId(List<String> id) {
        // 空值安全处理
        if (id == null || id.isEmpty()) {
            return new ArrayList<>();
        }

        // Cypher 查询：批量ID匹配 → 找相邻【文档】节点 → 只返回文档节点属性
        String cypher = """
                MATCH (n)-[r]-(m:文档) 
                WHERE n.entityId IN $ids
                RETURN DISTINCT m
                """;

        // 执行 Neo4j 查询
        return neo4jClient.query(cypher)
                .bind(id).to("ids")  // 绑定批量ID
                .fetch()
                .all()
                .stream()
                // 把查询结果转成 Map<String,String>
                .map(record -> {
                    Node node = (Node) record.get("m");
                    Map<String, String> resultMap = new HashMap<>();
                    node.asMap().forEach((key, value) ->
                            resultMap.put(key, value == null ? "" : value.toString())
                    );
                    return resultMap;
                })
                .toList();
    }

    // ==================== 新 Entity/Relation 模型的插入方法 ====================

    /**
     * 基于新 Entity 模型插入节点（CONTENT_ENTITY / DOCUMENT_ENTITY）
     * 使用 entityId 作为 MERGE 的唯一键
     */
    public Boolean insertEntity(Entity entity) {
        String label = switch (entity.getNodeType()) {
            case CONTENT_ENTITY -> "内容实体";
            case DOCUMENT_ENTITY -> "文档";
            case CLUSTER_ENTITY -> "主题聚类节点";
        };

        String cypher = String.format("""
                        MERGE (n:`%s` {entityId: $entityId})
                        ON CREATE SET n.entityName = $entityName,
                                      n.entityType = $entityType,
                                      n.entityDescription = $entityDescription,
                                      n.entityInfo = $entityInfo,
                                      n.fileId = $fileId,
                                      n.chunkId = $chunkId
                        ON MATCH SET n.entityName = $entityName,
                                    n.entityType = $entityType,
                                    n.entityDescription = $entityDescription,
                                    n.entityInfo = $entityInfo,
                                    n.fileId = $fileId,
                                    n.chunkId = $chunkId
                        RETURN n.entityId AS insertedId
                        """, label);

        try {
            List<String> result = neo4jClient.query(cypher)
                    .bindAll(Map.of(
                            "entityId", entity.getEntityId(),
                            "entityName", entity.getEntityName() != null ? entity.getEntityName() : "",
                            "entityType", entity.getEntityType() != null ? entity.getEntityType() : "",
                            "entityDescription", entity.getEntityDescription() != null ? entity.getEntityDescription() : "",
                            "entityInfo", entity.getEntityInfo() != null ? entity.getEntityInfo() : "",
                            "fileId", entity.getFileId() != null ? entity.getFileId() : "",
                            "chunkId", entity.getChunkId() != null ? entity.getChunkId() : ""
                    ))
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> (String) row.get("insertedId"))
                    .toList();

            if (result.isEmpty()) {
                log.warn("实体节点插入/更新失败: entityId={}", entity.getEntityId());
                return false;
            }
            log.info("实体节点插入/更新成功: entityId={}, entityName={}", entity.getEntityId(), entity.getEntityName());
            return true;
        } catch (Exception e) {
            log.error("实体节点插入Neo4j异常: entityId={}", entity.getEntityId(), e);
            return false;
        }
    }

    /**
     * 批量插入实体节点
     */
    public int insertEntities(List<Entity> entities) {
        int successCount = 0;
        for (Entity entity : entities) {
            if (Boolean.TRUE.equals(insertEntity(entity))) {
                successCount++;
            }
        }
        log.info("批量插入实体节点: 总数={}, 成功={}", entities.size(), successCount);
        return successCount;
    }

    /**
     * 基于新 Relation 模型创建关系
     * sourceId 和 targetId 是实体的 entityId
     */
    public Boolean createRelation(Relation relation) {
        // 关系类型使用 relationDescription，对特殊字符做安全处理（替换为下划线）
        String relType = relation.getRelationDescription() != null
                ? relation.getRelationDescription().replaceAll("[^\\w\\u4e00-\\u9fff]", "_")
                : "关联";

        // Neo4j 关系类型不能用参数绑定，需要动态拼接
        String cypher = String.format("""
                        MATCH (s {entityId: $sourceId})
                        MATCH (t {entityId: $targetId})
                        MERGE (s)-[r:`%s`]->(t)
                        SET r.keywords = $keywords,
                            r.relationDescription = $relationDescription
                        RETURN r
                        """, relType);

        try {
            Relationship result = neo4jClient.query(cypher)
                    .bindAll(Map.of(
                            "sourceId", relation.getSourceId(),
                            "targetId", relation.getTargetId(),
                            "keywords", relation.getKeywords() != null ? relation.getKeywords() : "",
                            "relationDescription", relation.getRelationDescription() != null ? relation.getRelationDescription() : ""
                    ))
                    .fetchAs(Relationship.class)
                    .one()
                    .orElse(null);

            if (result == null) {
                log.warn("关系创建失败: sourceId={}, targetId={}, relType={}", relation.getSourceId(), relation.getTargetId(), relType);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("关系插入Neo4j异常: sourceId={}, targetId={}, relType={}", relation.getSourceId(), relation.getTargetId(), relType, e);
            return false;
        }
    }

    /**
     * 批量创建关系
     */
    public int createRelations(List<Relation> relations) {
        int successCount = 0;
        for (Relation relation : relations) {
            if (Boolean.TRUE.equals(createRelation(relation))) {
                successCount++;
            }
        }
        log.info("批量创建关系: 总数={}, 成功={}", relations.size(), successCount);
        return successCount;
    }
}
