package com.bistu.hkgrag.dao.neo4j.service;

import com.bistu.hkgrag.dao.neo4j.model.Triple;
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
     * 向Neo4j中插入节点
     *
     * @param properties 待插入的节点及属性
     * @return 是否插入成功
     */
    public Boolean insertNode(Map<String, Object> properties) {
        //获取节点的类型,节点只有类型和属性
        String label = (String) properties.get("label");
        String cypher = String.format(
                "MERGE (n:`%s` {id:$props.id}) " +
                        "ON CREATE SET n += $props " +
                        "RETURN n.id AS insertedId", label);
        try {
            List<String> insertedIds = neo4jClient.query(cypher)
                    .bindAll(Map.of("props", properties))
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> (String) row.get("insertedId"))
                    .toList();
            if (insertedIds.isEmpty()) {
                // 节点已存在，没有新插入
                log.info("节点Id已经存在，插入失败");
                return false;
            } else {
                // 成功插入节点
                log.info("节点插入成功，Id为[{}]", insertedIds);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("节点插入Neo4j异常");
            return false;
        }
    }


    /**
     * 建立文档节点与文件内容节点之间的关系
     *
     * @param triple 关系三元组
     * @return 是否插入成功
     */
    public Boolean createRelationship(Triple triple) {

        String cypher = String.format("""
                        MATCH (s:%s {id: $subjectId})
                        MATCH (o:%s {id: $objectId})
                        MERGE (s)-[r:`%s`]->(o)
                        ON CREATE SET r.id = $id
                        RETURN r
                        """,
                triple.getSubjectType(),
                triple.getObjectType(),
                triple.getPredicate()
        );

        Map<String, Object> params = Map.of(
                "subjectId", triple.getSubjectId(),
                "objectId", triple.getObjectId(),
                "id", triple.getId());
        Relationship relationship = neo4jClient.query(cypher)
                .bindAll(params)
                .fetchAs(Relationship.class)
                .one()
                .orElse(null);

        return relationship != null;
    }

    /**
     * 根据节点id查找节点
     *
     * @param id 节点id
     * @return 节点所有属性信息
     */
    public Map<String, Object> findNodeById(String id) {
        String cypher = """
                MATCH (n {id:$id})
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
     * 根据id列表获取图片内对应节点及属性
     * @param id 待检索id列表
     * @return
     */
    public List<Map<String, Object>> findNodeByEntitiesId(List<String> id) {
        // 空值安全处理
        if (id == null || id.isEmpty()) {
            return new ArrayList<>();
        }

        // Cypher：根据节点自身的 id 属性查询
        String cypher = """
                MATCH (n)
                WHERE n.id IN $ids
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
                MATCH (n {id: $id})-[r]-(m)
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
                MATCH (n {id: $id})-[r]-(m:文档) 
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
                WHERE n.id IN $ids
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
}
