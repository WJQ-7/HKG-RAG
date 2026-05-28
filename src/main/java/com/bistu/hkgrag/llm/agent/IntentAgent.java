package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IntentAgent {
    private static Logger log = LoggerFactory.getLogger(IntentAgent.class);
    private ChatClient chatClient;
    private final RetrievalTools retrievalTools;
    private ChatClient.Builder chatClientBuilder;


    public IntentAgent(
            @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
            @Autowired RetrievalTools retrievalTools,
            @Autowired ChatClient.Builder chatClientBuilder
    ) {

        this.chatClient = chatClient;
        this.retrievalTools = retrievalTools;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Schema(description = "工具调用轨迹记录")
    public record ToolCallTrace(

            @JsonProperty(required = true)
            @Schema(description = "工具名称")
            String toolName,

            @JsonProperty(required = true)
            @Schema(description = "工具调用原因")
            String reason,

            @JsonProperty(required = true)
            @Schema(description = "工具输入参数")
            String input,

            @JsonProperty(required = true)
            @Schema(description = "工具返回结果摘要")
            String outputSummary,

            @JsonProperty(required = true)
            @Schema(description = "工具是否成功")
            boolean success,

            @JsonProperty(required = true)
            @Schema(description = "工具返回结果数量")
            int resultCount

    ) {}

    @Schema(description = "子问题回答结果")
    public record SubQuestionAnswer(

            @JsonProperty(required = true)
            @Schema(description = "子问题")
            String subQuestion,

            @JsonProperty(required = true)
            @Schema(description = "工具调用轨迹")
            List<ToolCallTrace> toolCalls,

            @JsonProperty(required = true)
            @Schema(description = "子问题答案")
            String answer,

            @JsonProperty(required = true)
            @Schema(description = "子问题是否成功")
            boolean success,

            @JsonProperty(required = true)
            @Schema(description = "失败原因")
            String failureReason

    ) {}

    @Schema(description = "回答结果")
    public record AnswerResult(
            @JsonProperty(required = true)
            @Schema(description = "用户问题")
            String question,

            @JsonProperty(required = true)
            @Schema(description = "最终答案")
            String finalAnswer,

            @JsonProperty(required = true)
            @Schema(description = "子问题回答")
            List<SubQuestionAnswer> subAnswers,

            @JsonProperty(required = true)
            @Schema(description = "全局工具调用轨迹")
            List<ToolCallTrace> allToolCalls,

            @JsonProperty(required = true)
            @Schema(description = "是否成功")
            boolean success,

            @JsonProperty(required = true)
            @Schema(description = "失败原因")
            String failureReason

    ) {}

    //TODO 这里可以写一个思维链作为系统提示词 遇到什么问题执行固定使用什么工具
    public static final String system_COT = """
            你是一个“意图解析 + 工具调用智能体”。
            
            你的职责：
            1. 根据输入的结构化问题分析结果，判断需要调用哪些工具
            2. 调用工具获取知识
            3. 基于工具结果生成最终答案
            
            【重要规则】
            - 只要涉及实体 → 必须调用 searchEntityByName 或 searchFileByEntity
            - 涉及主题/聚类 → 必须调用 searchFileByTopic
            - 无法判断 → 调用 searchFileByQuery（兜底）
            - 不允许直接编造答案
            
            你必须优先使用工具，不允许跳过工具直接回答。
            """;
    public static final String Retrieval_Prompt= """
            # 角色：你是一个“意图解析与工具调用智能体”。
            
            # 任务：
            1.你需要根据“问题分析智能体”的结果去选择检索工具，将问题分析智能体的相关结果作为检索工具的参数。
            2.自主判断应该调用哪些检索工具，
            3.通过工具获取知识，
            4.依据获取的相关知识回答用户问题。
            
            # 最终回答要求
            1. 必须基于工具返回结果回答
            2. 不允许虚构信息
            3. 回答尽量完整
            4. 如果工具未检索到内容，需要明确说明
            5. 多跳问题需要进行综合分析后再回答
          
            # 问题分析智能体结果
            用户问题：%s
            问题类型：%s
            相关实体：%s
            相关主题：%s
            子问题：%s
            检索规划：%s
            规划原因：%s
            
            请开始自主选择工具并完成问题回答。
            """;

    public AnswerResult generationAnswer(String question,QuestionAnalysisAgent.RetrievalPlanResponse plan) {

        String user_prompt = Retrieval_Prompt.formatted(
                question,
                plan.queryType(),
                plan.entities(),
                plan.topics(),
                plan.subQuestions(),
                plan.retrievalPlan(),
                plan.reasoning()
        );
        String system_prompt=system_COT;
        ChatResponse response = chatClient.prompt()
                .system(system_prompt)
                .user(user_prompt)
                .tools(retrievalTools)
                .call()
                .chatResponse();



        // 获取回答
        String finalAnswer = response.getResult().getOutput().getText();
        boolean success = (finalAnswer != null && !finalAnswer.isBlank());

        // 工具轨迹（空列表，可后续扩展解析真实 toolCall）
        List<ToolCallTrace> toolCallTraces = new ArrayList<>();
        String failureReason = success ? "" : "LLM 未返回有效答案";

        SubQuestionAnswer subAnswer = new SubQuestionAnswer(
                question,
                toolCallTraces,
                finalAnswer,
                success,
                failureReason
        );

        // 最终结果（严格匹配 6 个参数）
        return new AnswerResult(
                question,
                finalAnswer,
                List.of(subAnswer),
                toolCallTraces,
                success,
                failureReason
        );

    }




















//    /**
//     * 子意图解析智能体结果类
//     * @param keyEntities 子问题涉及的关键实体列表
//     * @param constraints 子问题涉及的度维度约束列表
//     * @param steps 子问题的检索规划
//     */
//    @Schema(description = "子意图解析智能体结果")
//    public record IntentParsingResult(
//            @JsonProperty(value = "keyEntities", required = true)
//            @Schema(description = "子问题中涉及的实体关键词列表", requiredMode = Schema.RequiredMode.REQUIRED)
//            List<String> keyEntities,
//
//            @JsonProperty(value = "constraints")
//            @Schema(description = "子问题中涉及的主题、文档类型、时间约束、发文机构以及所需要的知识类型等约束（键值对）")
//            Map<String, String> constraints,
//
//            @JsonProperty(value = "steps")
//            @Schema(description = "子问题的检索规划")
//            List<String> steps
//    ) {
//    }
//
//
//    /**
//     * 模型根据子问题进行意图解析
//     * 解析出 子问题涉及的关键实体列表、问题涉及的度维度约束列表
//     *
//     * @param subquestion 子问题
//     * @return 结构化结果
//     */
//    public IntentParsingResult intentParsing(String subquestion) {
//        try {
//            String IntentParsing_Prompt = String.format(PromptTemplates.Agent_IntentParsing, subquestion);
//            IntentParsingResult result = chatClient.prompt()
//                    .user(IntentParsing_Prompt)
//                    .call()
//                    .entity(IntentParsingResult.class);
//            log.info("意图解析智能体分析成功{}", result);
//            return result;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("意图解析智能体分析失败");
//            return null;
//        }
//    }
//
//    public enum ToolType {
//        KG,//知识图谱
//        VECTOR //向量
//    }
//
//    /**
//     * LLM选择适用什么检索工具
//     *
//     * @param parsingResult 子意图分析结果（关键词、相关约束）
//     * @return 选择的工具类型
//     */
//    public String decideTool(IntentParsingResult parsingResult) {
//        try {
//            String prompt = """
//                    你是一个检索策略的决策专家，请根据以下信息选择最合适的检索方式。
//
//                    可选的工具：
//                    1.KG:适用于明确实体查询（如人名、机构、具体对象）
//                    2.VECTOR:适用于模糊查询、语义匹配、多条件检索
//
//                    输入信息：
//                    相关实体：%s
//                    子问题相关约束：%s
//
//                    """.formatted(parsingResult.keyEntities(), parsingResult.constraints());
//
//            ChatResponse response = chatClient.prompt()
//                    .user(prompt)
//                    .tools(entityService, neo4jService)
//                    .call()
//                    .chatResponse();
//
//            return response.getResult().getOutput().toString();
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("模型选择工具失败");
//        }
//        return null;
//    }
//
//    /***
//     * 模型根据检索结果回答问题
//     * @param subquestion 子问题
//     * @param retrievalResult 子问题的检索结果
//     * @return 子问题的答案
//     */
//    public String solveSubQuestion(String subquestion, String retrievalResult) {
//        String prompt = """
//                任务：根据提供的检索结果，精准回答子问题。
//                要求：
//                1.必须**严格依据检索结果**作答，禁止编造、扩展无关内容。
//                2.答案要简洁、准确、完整，直接回应问题。
//                3.若检索结果无相关信息，可以明确回答："根据现有检索结果，无法回答该问题"。
//
//                子问题：%s
//
//                检索结果：%s
//                """.formatted(subquestion, retrievalResult);
//        return chatClient.prompt().user(prompt).call().content();
//    }

}
