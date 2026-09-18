package com.bistu.hkgrag.dao.neo4j.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Relation {
    /**
     * 源实体Id
     */
    public String sourceId;
    /**
     * 目标实体Id
     */
    public String targetId;
    /**
     * 源实体原始名称
     */
    public String sourceName;
    /**
     * 目标实体原始名称
     */
    public String targetName;
    /**
     * 源实体名和目标实体名泛化后的关键词（用于检索匹配）
     */
    public String keywords;
    /**
     * 关系描述
     */
    public String relationDescription;
}
