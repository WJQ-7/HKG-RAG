package com.bistu.hkgrag.llm.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TestMultiAgent {
    private final Logger log = LoggerFactory.getLogger(TestMultiAgent.class);
    private final QuestionAnalysisAgent questionAnalysisAgent;
    private final IntentAgent intentAgent;
    private final EvaluationAgent evaluationAgent;
    private static final int MAX_RETRY = 3;

    public TestMultiAgent(@Autowired QuestionAnalysisAgent questionAnalysisAgent,
                          @Autowired IntentAgent intentAgent,
                          @Autowired EvaluationAgent evaluationAgent) {
        this.questionAnalysisAgent = questionAnalysisAgent;
        this.intentAgent = intentAgent;
        this.evaluationAgent = evaluationAgent;
    }

    /**
     * 每轮生成记录
     */
    public record Generation(

            QuestionAnalysisAgent.RetrievalPlanResponse retrievalPlan,

            IntentAgent.AnswerResult answerResult,

            EvaluationAgent.EvaluationResult evaluationResult

    ) {
    }

    public String testMultiAgent(String question) {

        String conversationId =
                java.util.UUID.randomUUID().toString();

        List<String> memory = new ArrayList<>();
        List<Generation> generations = new ArrayList<>();

        String reflectionSuggestion = "";
        for (int round = 1; round <= MAX_RETRY; round++) {
            log.info("========== 开始第 {} 轮 Agent 执行 ==========", round);

            /**
             * Step1：问题分析智能体
             */
            QuestionAnalysisAgent.RetrievalPlanResponse plan =
                    questionAnalysisAgent.retrievePlan(
                            question,
                            generations
                    );

            log.info("问题分析结果：{}", plan);

            /**
             * Step2：意图解析智能体（自主工具调用）
             */
            IntentAgent.AnswerResult answerResult =
                    intentAgent.generationAnswer(question, plan,conversationId);

           // log.info("答案生成结果：{}", answerResult);

            /**
             * Step3：答案评估智能体
             */
            EvaluationAgent.EvaluationResult evaluationResult =
                    evaluationAgent.evaluate(question,answerResult);

            log.info("答案评估结果：{}", evaluationResult);

            /**
             * 保存本轮记录
             */
            generations.add(
                    new Generation(
                            plan,
                            answerResult,
                            evaluationResult
                    )
            );

            /**
             * PASS：直接返回
             */
            if (evaluationResult.success().equals(EvaluationAgent.EvaluationResult.Evaluation.PASS)) {
                log.info("答案评估通过，结束流程");
                return answerResult.finalAnswer();
            }


            /**
             * FAIL：记录反思建议
             */
            reflectionSuggestion = evaluationResult.suggestion();

            memory.add(reflectionSuggestion);

            log.warn("答案评估未通过，准备进入下一轮反思");

        }

        /**
         * 超过最大轮数
         */
        log.error("达到最大反思轮数，返回最后一次结果");

        if (!generations.isEmpty()) {

            Generation lastGeneration =
                    generations.get(generations.size() - 1);

            return lastGeneration.answerResult().finalAnswer();
        }

        return "未能成功生成答案";
    }


}
