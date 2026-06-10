package com.bistu.hkgrag.llm.model.agent;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 数据集
 */
@Setter
@Getter
@NoArgsConstructor
public class TestSet {
    /**
     * 问题来源文件Id
     */
    public String fileId;
    /**
     * 问题类型（单跳、同文档多跳）
     */
    public Type type;
    /**
     * 问题
     */
    public String question;
    /**
     * 答案
     */
    public String answer;
    /**
     * 原文依据
     */
    public String according;


    public enum Type {
        SINGLE_HOP,SAME_DOC_Multi_HOP,CROSS_DOC_Multi_HOP,SUMMARY_LEVEL
    }
}
