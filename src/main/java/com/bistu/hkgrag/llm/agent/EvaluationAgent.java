package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.config.PromptConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;



/**
 * 答案评估智能体
 */
@Service
public class EvaluationAgent {
    private static Logger log = LoggerFactory.getLogger(EvaluationAgent.class);
    private final EvidenceStore evidenceStore;
    private ChatClient chatClient;
    private PromptConfig promptConfig;

    public EvaluationAgent( @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                            @Autowired EvidenceStore evidenceStore,
                            @Autowired PromptConfig promptConfig) {
        this.chatClient = chatClient;
        this.evidenceStore = evidenceStore;
        this.promptConfig = promptConfig;
    }

    @Schema(description = "问答评估结果")
    public record EvaluationResult(
            @JsonProperty(value = "success",required = true)
            @Schema(description = "当前答案能否成功回答用户提问", requiredMode = Schema.RequiredMode.REQUIRED)
            Evaluation success,

            @JsonProperty(value = "reason",required = true)
            @Schema(description = "答案通过/不通过的理由", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonPropertyDescription("答案通过或不通过的理由是什么")
            String reason,

            @JsonProperty(value = "diagnosis")
            @Schema(description = "失败诊断（仅FAIL时填写），用于指导下一轮问题分析智能体调整策略")
            Diagnosis diagnosis
    ) {
        public enum Evaluation {
            PASS, FAIL
        }

        /**
         * 结构化诊断信息，指导问题分析智能体下一轮调整策略
         */
        public enum ProblemType {
            WRONG_RELATION_PATH, INSUFFICIENT_EXPANSION, WRONG_TOPICS, NEED_QUESTION_REWRITE, WRONG_QUERY_TYPE
        }

        @Schema(description = "失败诊断信息")
        public record Diagnosis(
                @JsonProperty(value = "problemType", required = true)
                @Schema(description = "诊断出的根本问题类型")
                ProblemType problemType,

                @JsonProperty(value = "detail", required = true)
                @Schema(description = "具体诊断说明")
                String detail,

                @JsonProperty(value = "suggestionDetail")
                @Schema(description = "修改建议：根据problemType填写对应内容，如关系语义描述、建议主题、改写问题、正确类型等")
                String suggestionDetail,

                @JsonProperty(value = "nextRoundStrategy", required = true)
                @Schema(description = "下一轮应优先采取的策略建议")
                String nextRoundStrategy
        ) {}
    }

    /**
     * 答案评估智能体对用户问题与生成得初始答案进行评估
     * @param question 用户问题
     * @param plan 问题分析智能体的检索计划（queryType、keywords、topics）
     * @param answerResult 意图解析智能体的返回结果
     * @return
     */
    public EvaluationResult evaluate(String question, QuestionAnalysisAgent.RetrievalPlanResponse plan, RetrievalAgent.AnswerResult answerResult) {
        String evidence=Utils.buildEvidence(answerResult,evidenceStore);
        String AnswerEvaluate_Prompt=String.format(promptConfig.agentEvaluation(),question,plan.toString(),answerResult.finalAnswer(),evidence);
        ChatOptions options = OpenAiChatOptions.builder()
                .responseFormat(
                        ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build()
                )
                .temperature(0.1)
                .build();
        EvaluationResult evaluationResult=chatClient.prompt()
                .user(AnswerEvaluate_Prompt)
                .options(options)
                .call()
                .entity(EvaluationResult.class);
        return evaluationResult;
    }

}
