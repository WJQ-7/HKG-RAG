package com.bistu.hkgrag.dao.milvus.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class DoCluster {
    /**
     * 实体Id
     */
    public String clusterId;
    /**
     * 实体名
     */
    public String clusterName;
    /**
     * 实体描述
     */
    public String description;

    /**
     * 实体名向量
     */
    public float[] clusterNameVector;
    /**
     * 描述向量
     */
    public float[] descriptionVector;
    /**
     * 相似度分数
     */
    public float clusterNameScore;
    /**
     * 描述的相似度分数
     */
    public float descriptionScore;

}
