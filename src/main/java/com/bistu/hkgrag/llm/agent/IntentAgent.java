package com.bistu.hkgrag.llm.agent;


import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;


@Service
public class IntentAgent {
    private static Logger log = LoggerFactory.getLogger(IntentAgent.class);
    private ChatClient chatClient;
    private final RetrievalTools retrievalTools;
    private final ToolCallAdvisor toolCallAdvisor;

    private final ToolCallingManager toolCallingManager;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;
    private ChatMemory chatMemory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final EvidenceStore evidenceStore;
    /**
     * ✅ 用于记录 tool 调用轨迹（关键）
     */
    private final ThreadLocal<List<ToolCallTrace>> toolTraceHolder =
            ThreadLocal.withInitial(ArrayList::new);




    public IntentAgent(
            @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
            @Autowired RetrievalTools retrievalTools,
            @Autowired ToolCallAdvisor toolCallAdvisor,
            @Autowired ToolCallingManager toolCallingManager,
            @Autowired MessageChatMemoryAdvisor chatMemoryAdvisor,
            @Autowired ChatMemory chatMemory,
            @Autowired EvidenceStore evidenceStore

    ) {

        this.chatClient = chatClient;
        this.retrievalTools = retrievalTools;
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolCallingManager = toolCallingManager;
        this.chatMemoryAdvisor = chatMemoryAdvisor;
        this.chatMemory = chatMemory;
        this.evidenceStore = evidenceStore;
    }

    @Schema(description = "工具调用轨迹记录")
    public record ToolCallTrace(

            @JsonProperty(required = true)
            @Schema(description = "工具名称")
            String toolName,

            @JsonProperty(required = true)
            @Schema(description = "工具输入参数")
            String input,

            @JsonProperty(required = true)
            @Schema(description = "证据ID")
            String evidenceId,

            @JsonProperty(required = true)
            @Schema(description = "工具返回结果摘要")
            String summary

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
            boolean success
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
            List<ToolCallTrace> allToolCalls
    ) {}

    //TODO 这里可以写一个思维链作为系统提示词 遇到什么问题执行固定使用什么工具
    public static final String system_COT = """
            你是一个“意图解析 + 工具调用智能体”。
            
            你的职责：
            1. 根据输入的结构化问题分析结果，判断需要调用哪些工具
            2. 调用工具获取知识
            
            【工具选择规则】
            1. 用户问题询问：
               - 原因
               - 为什么
               - 如何
               - 背景
               - 经过
               - 影响
               - 事件说明
            对于所有问题可以优先使用 searchFileByQuery(原始用户问题)，回答不出可可以考虑其他问题
            2. 当问题查询的是人名、地名、机构名及其属性时调用。
               - 某个人是谁
               - 某机构是什么
               - 某项目负责人
               - 某实体属性
               使用 searchEntityByName
            
            4. 用户问题涉及主题归纳：
               使用 searchFileByTopic
            5. 不确定时：
               优先 searchFileByQuery
            
            
            【工具调用约束】
            - 每轮至少选用一个检索工具
            - 当调用工具时，必须优先使用“问题分析智能体结果”中的内容作为参数来源。
            - 禁止重复调用“相同工具 + 相同语义参数”
            - 若某工具已对某语义问题返回结果，则必须：
              1. 更换检索维度（实体 → 主题 → query）
              2. 或直接终止该工具调用
            - 禁止通过改写词序、增删无意义词重复查询
            - 每次工具调用必须引入新的信息增量目标
            - 工具调用必须形成“信息递进链”，不能循环
            
            
            你必须优先使用工具，不允许跳过工具直接回答。
            """;
    public static final String Retrieval_Prompt= """
            # 角色：你是一个“意图解析与工具调用智能体”。
            
            # 任务：
            1.你需要根据“问题分析智能体”的结果去选择检索工具，将问题分析智能体的相关结果作为检索工具的参数。
            2.自主判断应该调用哪些检索工具。
            3.考虑使用哪个工具更可能获得问题/子问题的答案。
          
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

    public static final String ANSWER_PROMPT = """
            # 角色：你是一个问答智能体。
            # 任务：
            1.你需要根据相关检索结果回答用户问题。
            2.简单具体的问题需要依据检索结果进行简洁地回答，概述性问题需要更为发散的回答。
            
            # 回答要求
            1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
            2. 回答语言要简洁、准确、通顺，符合用户问题的要求。
            3. 如果多个文档出现相同信息，进行汇总
            4. 如果证据不足，明确说明无法回答
            
            ## 输入
            用户问题：
            %s
            相关信息：
            %s
            
            要求：
            
            1.禁止使用外部知识
            2.禁止编造
            3.多个证据需要进行汇总
            4.证据不足时明确说明无法回答
            5.回答尽量完整
            """;

    public AnswerResult generationAnswer(String question,QuestionAnalysisAgent.RetrievalPlanResponse plan, String conversationId) {

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
                .advisors(advisorSpec -> advisorSpec.param(
                        ChatMemory.CONVERSATION_ID,
                        conversationId
                ))
                .advisors(chatMemoryAdvisor,toolCallAdvisor)
                .call()
                .chatResponse();

//        List<Message> messages =
//                chatMemory.get(conversationId);
//
//        messages.forEach(msg -> {
//            log.info(
//                    "type={} content={}",
//                    msg.getClass().getSimpleName(),
//                    msg.getText()
//            );
//        });
        //获取工具调用记录
        List<ToolCallTrace> toolCallTraces =
                extractToolTraces(conversationId);


        // 获取回答
        String finalAnswer = response.getResult().getOutput().getText();
        boolean success = (finalAnswer != null && !finalAnswer.isBlank());

        SubQuestionAnswer subAnswer = new SubQuestionAnswer(
                question,
                toolCallTraces,
                finalAnswer,
                success
        );

        // 最终结果（严格匹配 6 个参数）
        return new AnswerResult(
                question,
                finalAnswer,
                List.of(subAnswer),
                toolCallTraces
        );

    }

    /**
     * 提取调用工具的记录
     * @param conversationId 记忆的id
     * @return 自定义工具记录
     */
    private List<ToolCallTrace> extractToolTraces(String conversationId) {
        //1.根据对话id从记忆中获取完整的消息列表
        List<Message> messages = chatMemory.get(conversationId);
        List<ToolCallTrace> toolCallTraces = new ArrayList<>();
        for(int i=0; i<messages.size(); i++) {
            Message message = messages.get(i);
            //跳过非AssistantMessage
            if(!(message instanceof AssistantMessage assistantMessage)) {
                continue;
            }
            //判断是否调用了工具 没有调用直接跳过
            if(assistantMessage.getToolCalls()==null || assistantMessage.getToolCalls().isEmpty()) {
                continue;
            }


            for(AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                String evidenceId = "";
                String summary = "";
                //检查当前消息的下一条消息是不是工具响应消息
                //如果是就把工具返回结果取出
                if(i+1<messages.size() && messages.get(i+1) instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        try {

                            String responseData = response.responseData();

                            if (!StringUtils.hasText(responseData)
                                    || "null".equalsIgnoreCase(responseData)) {

                                log.warn("工具返回为空: {}", toolCall.name());
                                continue;
                            }

                            RetrievalTools.RetrievalResult result =
                                    mapper.readValue(
                                            responseData,
                                            RetrievalTools.RetrievalResult.class
                                    );

                            if (result == null) {
                                log.warn("工具返回结果解析后为空: {}", responseData);
                                continue;
                            }
                            evidenceId = result.evidentId();
                            summary = result.summary();

                        } catch (JsonProcessingException e) {
                            log.error("解析工具返回结果失败: {}",response.responseData(),e);
                        }

                    }
                }
                toolCallTraces.add(new ToolCallTrace(
                        toolCall.name(),
                        toolCall.arguments(),
                        evidenceId,
                        summary
                        ));
            }
        }
        return toolCallTraces;
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
