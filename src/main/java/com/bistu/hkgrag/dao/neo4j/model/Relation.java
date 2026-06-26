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
     * 源实体名+目标实体名
     */
    public String keywords;
    /**
     * 关系描述
     */
    public String relationDescription;
}
