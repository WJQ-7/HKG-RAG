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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class IntentAgent {
    private static Logger log = LoggerFactory.getLogger(IntentAgent.class);
    private ChatClient chatClient;
    private EntityService entityService;
    private Neo4jService neo4jService;


    public IntentAgent(
                       @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                       @Autowired EntityService entityService,
                       @Autowired Neo4jService neo4jService
    ) {

        this.chatClient = chatClient;
        this.entityService = entityService;
        this.neo4jService = neo4jService;
    }


    /**
     * 子意图解析智能体结果类
     * @param keyEntities 子问题涉及的关键实体列表
     * @param constraints 子问题涉及的度维度约束列表
     * @param steps 子问题的检索规划
     */
    @Schema(description = "子意图解析智能体结果")
    public record IntentParsingResult(
            @JsonProperty(value = "keyEntities", required = true)
            @Schema(description = "子问题中涉及的实体关键词列表", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> keyEntities,

            @JsonProperty(value = "constraints")
            @Schema(description = "子问题中涉及的主题、文档类型、时间约束、发文机构以及所需要的知识类型等约束（键值对）")
            Map<String, String> constraints,

            @JsonProperty(value = "steps")
            @Schema(description = "子问题的检索规划")
            List<String> steps
    ) {
    }


    /**
     * 模型根据子问题进行意图解析
     * 解析出 子问题涉及的关键实体列表、问题涉及的度维度约束列表
     *
     * @param subquestion 子问题
     * @return 结构化结果
     */
    public IntentParsingResult intentParsing(String subquestion) {
        try {
            String IntentParsing_Prompt = String.format(PromptTemplates.Agent_IntentParsing, subquestion);
            IntentParsingResult result = chatClient.prompt()
                    .user(IntentParsing_Prompt)
                    .call()
                    .entity(IntentParsingResult.class);
            log.info("意图解析智能体分析成功{}", result);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("意图解析智能体分析失败");
            return null;
        }
    }

    public enum ToolType {
        KG,//知识图谱
        VECTOR //向量
    }

    /**
     * LLM选择适用什么检索工具
     *
     * @param parsingResult 子意图分析结果（关键词、相关约束）
     * @return 选择的工具类型
     */
    public String decideTool(IntentParsingResult parsingResult) {
        try {
            String prompt = """
                    你是一个检索策略的决策专家，请根据以下信息选择最合适的检索方式。
                    
                    可选的工具：
                    1.KG:适用于明确实体查询（如人名、机构、具体对象）
                    2.VECTOR:适用于模糊查询、语义匹配、多条件检索
                    
                    输入信息：
                    相关实体：%s
                    子问题相关约束：%s
                    
                    """.formatted(parsingResult.keyEntities(), parsingResult.constraints());

            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .tools(entityService, neo4jService)
                    .call()
                    .chatResponse();

            return response.getResult().getOutput().toString();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("模型选择工具失败");
        }
        return null;
    }

    /***
     * 模型根据检索结果回答问题
     * @param subquestion 子问题
     * @param retrievalResult 子问题的检索结果
     * @return 子问题的答案
     */
    public String solveSubQuestion(String subquestion, String retrievalResult) {
        String prompt = """
                任务：根据提供的检索结果，精准回答子问题。
                要求：
                1.必须**严格依据检索结果**作答，禁止编造、扩展无关内容。
                2.答案要简洁、准确、完整，直接回应问题。
                3.若检索结果无相关信息，可以明确回答："根据现有检索结果，无法回答该问题"。
                
                子问题：%s
                
                检索结果：%s
                """.formatted(subquestion, retrievalResult);
        return chatClient.prompt().user(prompt).call().content();
    }

}
