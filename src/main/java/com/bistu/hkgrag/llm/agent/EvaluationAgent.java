package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;


@Service
public class EvaluationAgent {
    private static Logger log = LoggerFactory.getLogger(EvaluationAgent.class);
    private ChatClient chatClient;

    public EvaluationAgent( @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient) {

        this.chatClient = chatClient;
    }

    @Schema(description = "问答评估结果")
    public record EvaluationResult(
            @JsonProperty(value = "success",required = true)
            @Schema(description = "当前答案能否回答用户提问", requiredMode = Schema.RequiredMode.REQUIRED)
            Evaluation success,

            @JsonProperty(value = "reason",required = true)
            @Schema(description = "答案通过/不通过的理由", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonPropertyDescription("答案通过或不通过的理由是什么")
            String reason,

            @JsonProperty(value = "suggestion")
            @Schema(description = "不通过时的检索建议/缺失信息说明")
            String suggestion
    ) {
        public enum Evaluation {
            PASS, FAIL
        }
    }

    /**
     * 答案评估智能体对用户问题与生成得初始答案进行评估
     * @param question 用户问题
     * @param initAnswer 生成得初始答案
     * @param according 依据
     * @return 评估结果
     */
    public EvaluationResult evaluate(String question,String initAnswer,String according) {
        String AnswerEvaluate_Prompt=String.format(PromptTemplates.Agent_Evaluation,question,initAnswer,according);
        EvaluationResult evaluationResult=chatClient.prompt()
                .user(AnswerEvaluate_Prompt)
                .call()
                .entity(EvaluationResult.class);
        return evaluationResult;
    }

}
