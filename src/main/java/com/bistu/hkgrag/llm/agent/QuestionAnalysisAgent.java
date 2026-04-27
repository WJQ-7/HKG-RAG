package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.llm.model.agent.StepInfo;
import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class QuestionAnalysisAgent {
    private static Logger log = LoggerFactory.getLogger(QuestionAnalysisAgent.class);

    private ChatClient chatClient;

    public QuestionAnalysisAgent(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient) {

        this.chatClient = chatClient;
    }
    /**
     * 问题分析智能体的返回结果类
     * @param topics 用户原始问题中所涉及的主题
     * @param subQuestions 原始问题拆分出的子问题
     */
    @Schema(description = "问题分析智能体返回结果")
    public record QuestionAnalysisResult(
            @JsonProperty(value = "topics", required = true)
            @Schema(description = "用户原始问题中涉及的主题", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> topics,

            @JsonProperty(value = "subQuestions", required = true)
            @Schema(description = "原始问题拆分出的子问题", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> subQuestions
    ) {
    }

    @Schema(description = "问题分析智能体进行主题预识别返回结果")
    public record QuestionPreRecognitionResult(
            @JsonProperty(value = "relatedTopics", required = true)
            @Schema(description = "用户初始查询中涉及的相关主题",requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> relatedTopics
    ){

    }

    /**
     * 问题分析智能体对用户问题进行预识别
     * @param question
     * @return
     */
    public QuestionPreRecognitionResult userQuestionPreRecognition(String question){
        try{
            String questionPreRecognition_prompt=String.format(PromptTemplates.Agent_QuestionPreRecognition,question);
            QuestionPreRecognitionResult questionPreRecognitionResult=chatClient.prompt()
                    .user(questionPreRecognition_prompt)
                    .call().entity(QuestionPreRecognitionResult.class);
            log.info("问题分析智能体预识别问题相关主题成功");
            return questionPreRecognitionResult;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("问题分析智能体预识别问题相关主题失败");
            return null;
        }
    }

    /**
     * 调用模型让模型对用户问题进行主题识别、问题解析
     * 将用户问题拆分为子问题序列
     *
     * @param question 用户问题
     * @return 返回模型结果
     */
    public QuestionAnalysisResult userQuestionAnalyse(String question,String suggestion) {
        try {
            String questionAnalysis_prompt = String.format(PromptTemplates.Agent_QuestionAnalysis, question,suggestion);
            QuestionAnalysisResult result = chatClient.prompt().user(questionAnalysis_prompt)
                    .call().entity(QuestionAnalysisResult.class);
            log.info("模型对用户问题进行分析成功");
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("模型对用户问题进行分析失败");
            return null;
        }
    }


    /**
     * 问题分析智能体根据子问题和子答案进行初始答案的生成
     * @param stepInfoList
     * @return
     */
    public String initAnswerGeneration(List<StepInfo> stepInfoList) {

        if (stepInfoList == null || stepInfoList.isEmpty()) {
            return "子问题为空，无法回答问题";
        }
        try {
            //拼接子问题和子问题答案
            StringBuilder subInfoBuilder = new StringBuilder();
            for (StepInfo stepInfo : stepInfoList) {
                subInfoBuilder.append("子问题：").append(stepInfo.getSubQuestion())
                        .append("\n子答案：").append(stepInfo.getSubAnswer())
                        .append("\n");
            }
            String subInfo = subInfoBuilder.toString();
            String prompt = """
                     请根据下面的子问题和子答案，整合生成一段完整、通顺、简洁的回答，
                     直接回答用户的原始问题，不要分点、不要重复子问题。
                    
                     子问题与子答案如下：
                      %s
                    
                     请输出最终的整合答案：
                    """.formatted(subInfo);
            String initAnswer = chatClient.prompt().user(prompt).call().content();
            log.info("根据子问题和子答案生成初始答案成功！");
            return initAnswer;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("根据子问题和子答案生成初始答案失败");
            return "";
        }
    }

    public String initAnswerGeneration(String question,String retrievalResult) {
        if (question == null || question.isEmpty()) {
            log.error("问题分析智能体生成初始回答时问题为空");
            return "";
        }
        try{
            String prompt = """
                    #你是一个专业的文档问答助手。
                    
                    #任务：根据检索到的文档内容回答用户问题
                    
                    #要求
                    1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
                    2. 如果文档中没有相关答案，请直接回复：“根据提供的文档，未找到相关信息。”
                    3. 回答语言要简洁、准确、通顺，符合用户问题的要求。
                    4. 禁止出现与文档无关的内容、猜测性表述或主观评论。
                    
                    #输入：
                    用户问题：%s
                    相关文档：%s
                    """.formatted(question, retrievalResult);
            String response = chatClient.prompt(prompt).call().content();
            log.info("问题分析智能体生成初始答案成功");
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("问题分析智能体生成初始答案失败",e);
            return "";
        }
    }



}
