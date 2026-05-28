package com.bistu.hkgrag.llm.agent;


import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.ai.chat.model.ChatResponse;
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

    public QuestionAnalysisAgent(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient) {

        this.chatClient = chatClient;
    }

    /**
     * 问题类型
     */
    @Schema(description = "问题类型")
    public enum QueryType {
        /**
         * 简单事实查询
         */
        @Schema(description = "简单事实查询：用户询问明确事实、属性、联系方式、时间等。")
        FACT,
        /**
         * 总结归纳类
         */
        @Schema(description = "总结归纳类问题：用户需要宏观总结、风险归纳、整体概括。")
        SUMMARY,
        /**
         * 实体关系类
         */
        @Schema(description = "实体关系类问题：用户询问实体之间的关系、归属、关联。")
        RELATION,
        /**
         * 时间约束类
         */
        @Schema(description = " 时间约束类问题：问题中明确包含时间范围、时间条件。")
        TEMPORAL,
        /**
         * 多跳推理类
         */
        @Schema(description = " 多跳推理类问题：需要经过多步关系推理才能回答的问题需要多步关系推理的问题。")
        MULTI_HOP

    }

    @Schema(description = "动态检索计划")
    public record RetrievalPlanResponse(
            @JsonProperty(required = true)
            @Schema(description = "问题类型")
            QueryType queryType,

            @JsonProperty(required = true)
            @Schema(description = "是否需要问题拆解")
            boolean needDecomposition,

            @JsonProperty(required = true)
            @Schema(description = "子问题")
            List<String> subQuestions,

            @JsonProperty(required = true)
            @Schema(description = "相关实体")
            List<String> entities,

            @JsonProperty(required = true)
            @Schema(description = "相关主题")
            List<String> topics,

            @JsonProperty(required = true)
            @Schema(description = "检索规划")
            String retrievalPlan,

            @JsonProperty(required = true)
            @Schema(description = "检索规划原因")
            String reasoning
    ) {

    }

    public static final String retrievalPlan_Prompt = """
            #角色：你是一个检索规划智能体
            #任务：你的任务是根据用户的问题进行问题类型判断、拆解判断、实体抽取、主题归纳，最终生成检索执行计划
            
            ## 详细任务
            1. 判断问题类型（queryType）
            2. 判断是否需要问题拆解（needDecomposition）
            3. 如果需要拆解，则生成子问题（subQuestions）
            4. 提取问题中的关键实体（entities）
            5. 提取问题涉及的主题（topics）
            6. 生成检索执行方案（retrievalPlan）：清晰描述具体要执行的检索步骤、检索范围、检索策略
            7. 给出本次检索规划的原因（reasoning）
            
            ## 问题类型定义
            1. FACT
                简单事实查询：用户询问明确事实、属性、联系方式、时间等。
                示例：
                - 张三的电话是多少
                - XX项目负责人是谁
            2. SUMMARY
                总结归纳类问题：用户需要宏观总结、风险归纳、整体概括。
                示例：
                - 2025年有哪些项目风险
                - 当前工作的主要问题有哪些
            3. RELATION
                实体关系类问题：用户询问实体之间的关系、归属、关联。
                示例：
                - 张三负责哪些项目
                - 计算机学院有哪些老师
            4. TEMPORAL
                时间约束类问题：时间是检索过滤条件或推理核心。
                示例：
                - 2024年的涉密项目有哪些
                - 去年有哪些重要通知
            5. MULTI_HOP
                多跳推理类问题：需要经过多步关系推理才能回答的问题
                示例：
                - 张三所在部门参与的涉密项目有哪些
                - 北京大学陈老师的学生发表过哪些论文
            
            ## 问题拆解规则
            1. 默认不要拆解问题
            2. 只有以下情况才允许拆解：
                - 问题包含多个独立子任务
                - 问题存在复杂多跳推理
                - 一个问题同时询问多个目标
            3. 简单问题禁止拆解
            4. 如果不需要拆解：
                needDecomposition=false
                subQuestions=[]
            
            ## 实体抽取规则
            1.entities字段用于保存：人名、地名、机构名、项目名、系统名、专业术语等
            2.若没有明确实体，则返回空列表[]
            
            ## 主题提取规则
            示例：
            问题：“2025年的项目风险有哪些”
            topics：["项目风险","项目管理"]
            
     
            
            # 输入
            用户问题：
            %s
            上一轮反思建议：
            %s
            
            # 输出
            必须严格遵循以下格式：
            {"queryType":"枚举值","needDecomposition":布尔值,"subQuestions":["子问题1","子问题2"],"entities":["实体1","实体2"],"topics":["主题1","主题2"],"retrievalPlan":"具体检索执行计划","reasoning":"规划原因"}
            
            输出规则：
            1. 必须是单行有效 JSON，不允许换行
            2. queryType 只能是 FACT、SUMMARY、RELATION、TEMPORAL、MULTI_HOP
            3. needDecomposition 只能是 true 或 false
            4. 无内容时数组必须为空 []，不能为 null
            5. 禁止添加任何解释、说明、额外文字
            
            示例：
            {"queryType":"MULTI_HOP","needDecomposition":true,"subQuestions":["北京大学的陈老师是谁","陈老师的学生有哪些","学生发表过哪些论文"],"entities":["北京大学","陈老师"],"topics":["学术研究","论文发表"],"retrievalPlan":"1.检索北京大学陈老师的身份信息；2.检索该老师的学生名单；3.检索学生发表的论文信息","reasoning":"该问题需要多步推理才能得出答案，属于多跳推理类型，需要拆解为子问题，实体为北京大学和陈老师"}
            {"queryType":"FACT","needDecomposition":false,"subQuestions":[],"entities":["张三"],"topics":["联系方式","电话"],"retrievalPlan":"1.检索张三这个实体；2.检索张三的联系方式/电话","reasoning":"该问题是简单事实查询，无需拆解，直接检索张三的电话即可"}
            
            """;


    public RetrievalPlanResponse retrievePlan(String question, String reflectionSuggestion) {
        try{
            String prompt = retrievalPlan_Prompt.formatted(question, reflectionSuggestion);
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


//    /**
//     * 问题分析智能体的返回结果类
//     * @param topics 用户原始问题中所涉及的主题
//     * @param subQuestions 原始问题拆分出的子问题
//     */
//    @Schema(description = "问题分析智能体返回结果")
//    public record QuestionAnalysisResult(
//            @JsonProperty(value = "topics", required = true)
//            @Schema(description = "用户原始问题中涉及的主题", requiredMode = Schema.RequiredMode.REQUIRED)
//            List<String> topics,
//
//            @JsonProperty(value = "subQuestions", required = true)
//            @Schema(description = "原始问题拆分出的子问题", requiredMode = Schema.RequiredMode.REQUIRED)
//            List<String> subQuestions
//    ) {
//    }
//
//    @Schema(description = "问题分析智能体进行主题预识别返回结果")
//    public record QuestionPreRecognitionResult(
//            @JsonProperty(value = "relatedTopics", required = true)
//            @Schema(description = "用户初始查询中涉及的相关主题",requiredMode = Schema.RequiredMode.REQUIRED)
//            List<String> relatedTopics
//    ){
//
//    }
//
//    /**
//     * 问题分析智能体对用户问题进行预识别
//     * @param question
//     * @return
//     */
//    public QuestionPreRecognitionResult userQuestionPreRecognition(String question){
//        try{
//            String questionPreRecognition_prompt=String.format(PromptTemplates.Agent_QuestionPreRecognition,question);
//            QuestionPreRecognitionResult questionPreRecognitionResult=chatClient.prompt()
//                    .user(questionPreRecognition_prompt)
//                    .call().entity(QuestionPreRecognitionResult.class);
//            log.info("问题分析智能体预识别问题相关主题成功");
//            return questionPreRecognitionResult;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("问题分析智能体预识别问题相关主题失败");
//            return null;
//        }
//    }
//
//    /**
//     * 调用模型让模型对用户问题进行主题识别、问题解析
//     * 将用户问题拆分为子问题序列
//     *
//     * @param question 用户问题
//     * @return 返回模型结果
//     */
//    public QuestionAnalysisResult userQuestionAnalyse(String question,String suggestion) {
//        try {
//            String questionAnalysis_prompt = String.format(PromptTemplates.Agent_QuestionAnalysis, question,suggestion);
//            QuestionAnalysisResult result = chatClient.prompt().user(questionAnalysis_prompt)
//                    .call().entity(QuestionAnalysisResult.class);
//            log.info("模型对用户问题进行分析成功");
//            return result;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("模型对用户问题进行分析失败");
//            return null;
//        }
//    }
//
//
//    /**
//     * 问题分析智能体根据子问题和子答案进行初始答案的生成
//     * @param stepInfoList
//     * @return
//     */
//    public String initAnswerGeneration(List<StepInfo> stepInfoList) {
//
//        if (stepInfoList == null || stepInfoList.isEmpty()) {
//            return "子问题为空，无法回答问题";
//        }
//        try {
//            //拼接子问题和子问题答案
//            StringBuilder subInfoBuilder = new StringBuilder();
//            for (StepInfo stepInfo : stepInfoList) {
//                subInfoBuilder.append("子问题：").append(stepInfo.getSubQuestion())
//                        .append("\n子答案：").append(stepInfo.getSubAnswer())
//                        .append("\n");
//            }
//            String subInfo = subInfoBuilder.toString();
//            String prompt = """
//                     请根据下面的子问题和子答案，整合生成一段完整、通顺、简洁的回答，
//                     直接回答用户的原始问题，不要分点、不要重复子问题。
//
//                     子问题与子答案如下：
//                      %s
//
//                     请输出最终的整合答案：
//                    """.formatted(subInfo);
//            String initAnswer = chatClient.prompt().user(prompt).call().content();
//            log.info("根据子问题和子答案生成初始答案成功！");
//            return initAnswer;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("根据子问题和子答案生成初始答案失败");
//            return "";
//        }
//    }
//
//    public String initAnswerGeneration(String question,String retrievalResult) {
//        if (question == null || question.isEmpty()) {
//            log.error("问题分析智能体生成初始回答时问题为空");
//            return "";
//        }
//        try{
//            String prompt = """
//                    #你是一个专业的文档问答助手。
//
//                    #任务：根据检索到的文档内容回答用户问题
//
//                    #要求
//                    1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
//                    2. 如果文档中没有相关答案，请直接回复：“根据提供的文档，未找到相关信息。”
//                    3. 回答语言要简洁、准确、通顺，符合用户问题的要求。
//                    4. 禁止出现与文档无关的内容、猜测性表述或主观评论。
//
//                    #输入：
//                    用户问题：%s
//                    相关文档：%s
//                    """.formatted(question, retrievalResult);
//            String response = chatClient.prompt(prompt).call().content();
//            log.info("问题分析智能体生成初始答案成功");
//            return response;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("问题分析智能体生成初始答案失败",e);
//            return "";
//        }
//    }
//


}
