package com.bistu.hkgrag.llm.model.agent;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 智能体之间协作记录
 * @author wangjingqi
 */
@Setter
@Getter
@NoArgsConstructor
public class StepInfo {
    /**
     * 子问题
     */
    String subQuestion;
    /**
     * 子答案
     */
    String subAnswer;
    /**
     * 子答案的依据
     */
    String subAccording;
    /**
     * 后续建议
     */
    String subSuggestion;
}
