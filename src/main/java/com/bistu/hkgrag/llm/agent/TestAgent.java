package com.bistu.hkgrag.llm.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TestAgent {

    private final Logger log = LoggerFactory.getLogger(TestAgent.class);
    private final IntentAgent intentAgent;

    public TestAgent(@Autowired IntentAgent intentAgent) {
        this.intentAgent = intentAgent;
    }

    /**
     * 消融实验A（阉割版 IntentAgent）：
     * - 仅保留 IntentAgent 的"自主选择工具 + 回答"能力
     * - 移除 QuestionAnalysisAgent（问题分析智能体）
     * - 移除 EvaluationAgent（评估智能体）
     * - 无多轮反思循环、无多智能体工作流，一次调用直接返回答案
     * - 提示词与完整版一致：systemCOT + autonomousRetrievalPrompt + intentAnswerPrompt
     *
     * @param question 用户问题
     * @return 最终答案
     */
    public String test_Agent(String question) {
        String conversationId = java.util.UUID.randomUUID().toString();
        log.info("消融实验：阉割版IntentAgent，问题[{}]，会话[{}]", question, conversationId);

        IntentAgent.AnswerResult result =
                intentAgent.generationAnswerAutonomous(question, conversationId);
        return result.finalAnswer();
    }

}
