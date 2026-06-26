package com.bistu.hkgrag.llm.agent;


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

    public QuestionAnalysisAgent(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient) {

        this.chatClient = chatClient;
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

    public static final String retrievalPlan_Prompt = """
            # 角色：你是一个问题分析智能体
            # 任务：分析用户问题，判断问题类型、抽取关键词并归纳主题
            
            ## 详细任务
            1. 判断问题类型（queryType）
            2. 抽取关键词（keywords）
            3. 提取相关主题（topics），用于主题检索
            
            ## 一、问题类型定义（queryType）
            
            ### 1. MULTI_HOP（多跳推理问题）
            需要经过多步关系推理才能回答的问题，涉及多个实体之间的关联跳转。
            特征：
            - 需要先找到A，再通过A找到B，再通过B找到C
            - 问题中存在隐含的中间推理步骤，必须跨多个实体才能回答，不能通过单次检索直接回答
            示例：
            - 张三所在部门参与的涉密项目有哪些
            - 北京大学陈老师的学生发表过哪些论文
            
            ### 2. GLOBAL_ABSTRACT（全局抽象问题）
            总结归纳类问题：用户需要宏观总结、风险归纳、整体概括等高层级认知的问题。
            示例：
                - 2025年有哪些项目风险
                - 当前工作的主要问题有哪些
            
            ### 3. SPECIFIC_DETAIL（具体细节问题）
            询问明确事实、属性、联系方式、时间、原因、具体要求等可直接定位的具体信息。
            特征：
            - 有明确的查询目标（某个人、某个机构、某个时间点、某个具体事件）
            - 答案通常是具体的描述性内容，可在文档中直接找到
            - **关键区分**：即使问题包含"为什么""什么原因"，只要问的是某个具体事件的原因/背景/细节，仍属于SPECIFIC_DETAIL，不需要多跳推理
            示例：
            - 张三的电话是多少
            - XX项目负责人是谁
            - 计算机学院的办公室在哪里
            - 关于XX考核的要求是什么
            
            ## 二、关键词抽取规则（keywords）
             1. keywords字段用于保存：人名、地名、机构名、项目名、系统名、专业术语等
            
            ## 三、主题提取规则（topics）
             1. 主题是对问题领域的高层概括，用于主题检索
            示例：
            问题：“2025年的项目风险有哪些”
            topics：["项目风险","项目管理"]
            
            ### 按诊断类型调整策略：
            
            #### WRONG_RELATION_PATH（多跳关系选择错误）：
            - 保持 queryType 为 MULTI_HOP 不变
            - 参考 suggestionDetail 中的建议关系调整关键词和 topics
            
            #### INSUFFICIENT_EXPANSION（多跳扩展不充分）：
            - 保持 queryType 为 MULTI_HOP 不变
            - 根据 suggestionDetail 补充关键词和 topics
            
            #### WRONG_TOPICS（主题不合适）：
            - 保持 queryType 为 GLOBAL_ABSTRACT 不变
            - 考虑是否将 topics 替换为 suggestionDetail 中建议的主题
            
            #### NEED_QUESTION_REWRITE（需要问题改写）：
            - 保持 queryType 不变
            - 根据 suggestionDetail 中的改写问题重新抽取关键词和 topics
            
            #### WRONG_QUERY_TYPE（问题类型定义错误）：
            - 将 queryType 改为 suggestionDetail 中建议的正确类型
            - 重新抽取关键词和 topics
           
            ## 反思与改进规则（非常重要）
                1. 哪些工具已经调用过
                2. 哪些检索关键词已经尝试过
                3. 哪些检索方向已经失败
                4. 评估智能体指出的问题是什么
                
            # 输入
            用户问题：
            %s
            历史执行记录与诊断结果：
            %s
            
            # 输出
            必须严格遵循以下格式（单行JSON，不换行）：
            {"queryType":"枚举值","keywords":["关键词1","关键词2"],"topics":["主题1","主题2"]}
            
            输出规则：
            1. 必须是单行有效 JSON，不允许换行
            2. queryType 只能是 MULTI_HOP、GLOBAL_ABSTRACT、SPECIFIC_DETAIL
            3. 无内容时数组必须为空 []，不能为 null
            4. 禁止添加任何解释、说明、额外文字
            
            示例1（多跳推理）：
            {"queryType":"MULTI_HOP","keywords":["张三","涉密项目"],"topics":["人员组织关系","项目管理"]}
            
            示例2（全局抽象）：
            {"queryType":"GLOBAL_ABSTRACT","keywords":["项目风险","2025年"],"topics":["项目风险管理","风险评估","项目安全"]}
            
            示例3（具体细节）：
            {"queryType":"SPECIFIC_DETAIL","keywords":["张三","电话"],"topics":["人员联系方式","教职工信息"]}
            
            """;


    public RetrievalPlanResponse retrievePlan(String question,List<MultiAgent.Generation> generations) {
        try{
            String history=Utils.buildHistory(generations);
            String prompt = retrievalPlan_Prompt.formatted(question, history);

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
