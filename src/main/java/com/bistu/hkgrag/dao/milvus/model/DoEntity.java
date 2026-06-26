package com.bistu.hkgrag.dao.milvus.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Milvus向量库中entity表(存kg的底层节点)
 * @author wangjingqi
 */
@Setter
@Getter
@NoArgsConstructor

public class DoEntity {
    /**
     * 实体Id
     */
    public String entityId;
    /**
     * 实体名
     */
    public String entityName;
    /**
     * 实体类型
     */
    public String entityType;
    /**
     * 实体描述
     */
    public String entityDescription;
    /**
     * 实体信息
     */
    public String entityInfo;

    /**
     * 实体名向量
     */
    public float[] entityNameVector;
    /**
     * 实体描述向量
     */
    public float[] entityDescriptionVector;

    /**
     * entityName 检索相似度分数
     */
    public float nameScore;
    /**
     * entityDescription 检索相似度分数
     */
    public float descriptionScore;

    /**
     * 融合后的最终相似度分数（用于排序）
     */
    public float score;

}
