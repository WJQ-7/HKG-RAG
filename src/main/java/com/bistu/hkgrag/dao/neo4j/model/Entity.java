package com.bistu.hkgrag.dao.neo4j.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 定义知识图谱中的节点结构
 */
@Getter
@Setter
public class Entity {

    /**
     * 实体唯一ID（UUID），在消歧阶段生成，用于 Neo4j MERGE 去重
     */
    public String entityId;
    /**
     * 节点类型（内容实体节点、文档节点、聚类节点）
     */
    public Type nodeType;
    /**
     * 实体名
     */
    public String entityName;
    /**
     * 实体类型
     */
    public String entityType;
    /**
     * 实体相关描述
     */
    public String entityDescription;

    /**
     * 实体信息
     * 对于内容实体节点：存储人物（职务、联系方式等）
     * 对于文档节点：存储文档的发文机构、时间等等信息
     * 对于聚类节点：无
     */
    public String entityInfo;
    /**
     * 所属文件Id
     */
    public String fileId;
    /**
     * 所属ChunkId
     */
    public String chunkId;


    public enum Type {
        CONTENT_ENTITY, DOCUMENT_ENTITY, CLUSTER_ENTITY
    }
}
