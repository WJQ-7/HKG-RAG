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
     * 实体名向量
     */
    public float[] entityNameVector;
    /**
     * 相似度分数
     */
    public float score;

}
