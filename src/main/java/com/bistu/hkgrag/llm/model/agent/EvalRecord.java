package com.bistu.hkgrag.llm.model.agent;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 评估指标记录
 */
@Setter
@Getter
@NoArgsConstructor
public class EvalRecord {
    /**
     * 问题来源文件Id
     */
    public String fileId;

    /**
     * 问题类型
     */
    public TestSet.Type type;
    /**
     * 问题
     */
    public String question;
    /**
     * 模型生成答案
     */
    public String predAnswer;
    /**
     * 数据集中标准答案
     */
    public String goldAnswer;
    /**
     * em精确匹配分数
     */
    public boolean em;
    /**
     * f1分数
     */
    public double f1;
    /**
     * 模型答案与真实答案之间最长公共子序列的长度与参考文本长度的比值
     */
    public double rougeL;

    /**
     * 模型答案在正确答案中匹配到的词数/正确答案总词数
     */
    public double recall;

}
