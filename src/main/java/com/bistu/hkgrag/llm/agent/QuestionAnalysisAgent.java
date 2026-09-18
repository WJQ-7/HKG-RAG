package com.bistu.hkgrag.llm.agent;


import com.bistu.hkgrag.config.PromptConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.util.List;


@Service
public class QuestionAnalysisAgent {
    private static Logger log = LoggerFactory.getLogger(QuestionAnalysisAgent.class);

    private ChatClient chatClient;
    private PromptConfig promptConfig;

    public QuestionAnalysisAgent(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                                  @Autowired PromptConfig promptConfig) {

        this.chatClient = chatClient;
        this.promptConfig = promptConfig;
    }

    /**
     * 问题类型
     */
    @Schema(description = "问题类型")
    public enum QueryType {
        /**
         * 多跳推理问题：需要经过多步关系推理才能回答
         */
        @Schema(description = "多跳推理问题：需要经过多步关系/实体之间的推理才能回答，涉及多个实体之间的关联跳转。")
        MULTI_HOP,

        /**
         * 全局抽象问题：需要宏观总结、归纳概括、趋势分析等
         */
        @Schema(description = "全局抽象问题：用户需要宏观总结、风险归纳、整体概括、趋势分析等高层级认知。")
        GLOBAL_ABSTRACT,

        /**
         * 具体细节问题：询问明确事实、属性、联系方式、时间等具体信息
         */
        @Schema(description = "具体细节问题：用户询问明确事实、属性、联系方式、时间、具体数据等可直接定位的信息。")
        SPECIFIC_DETAIL
    }

    @Schema(description = "问题分析结果")
    public record RetrievalPlanResponse(
            @JsonProperty(required = true)
            @Schema(description = "问题类型")
            QueryType queryType,

            @JsonProperty(required = true)
            @Schema(description = "关键词集合")
            List<String> keywords,

            @JsonProperty(required = true)
            @Schema(description = "相关主题")
            List<String> topics
    ) {

    }



    public RetrievalPlanResponse retrievePlan(String question,List<MultiAgent.Generation> generations) {
        try{
            String history=Utils.buildHistory(generations);
            String prompt = promptConfig.retrievalPlanPrompt().formatted(question, history);

            ChatOptions options = OpenAiChatOptions.builder()
                    .responseFormat(
                            ResponseFormat.builder()
                                    .type(ResponseFormat.Type.JSON_OBJECT)
                                    .build()
                    )
                    .temperature(0.1)
                    .build();
            RetrievalPlanResponse planResponse = chatClient.prompt()
                    .user(prompt)
                    .options(options)
                    .call()
                    .entity(RetrievalPlanResponse.class);
            return planResponse;

        } catch (Exception e) {
            e.printStackTrace();
            log.error("问题分析智能体进行检索规划失败", e);
            return null;
        }
    }
}
