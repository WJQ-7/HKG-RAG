package com.bistu.hkgrag.dao.neo4j.model;

import lombok.Getter;
import lombok.Setter;

/**
 * neo4j中以三元组的形式存在
 */
@Getter
@Setter
public class Triple {
    /**
     * 三元组id
     */
    private String id;
    /**
     * 主体实体
     */
    private String subject;
    /**
     * 谓词
     */
    private String predicate;
    /**
     * 客体实体
     */
    private String object;
    /**
     * 三元组类型
     */
    private Type type;
    /**
     * 主体类型
     */
    private String subjectType;
    /**
     * 客体类型
     */
    private String objectType;
    /**
     * 主体id
     */
    private String subjectId;
    /**
     * 客体id
     */
    private String objectId;


    /**
     * 三元组类型
     * NODE 节点 例如（subject="张三",subjectType="Person",type=NODE）
     * RELATION 关系 例如（subject="张三", predicate="就读于",object="北京大学",type=RELATION）
     * PROPERTY 属性 例如（subject="张三",predicate="年龄",object="20",type=PROPERTY）
     */
    public enum Type {
        NODE, RELATION, PROPERTY
    }
}
