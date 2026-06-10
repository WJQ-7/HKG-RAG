package com.bistu.hkgrag.llm.evaluation;

import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AssessService {
    private final Logger log = LoggerFactory.getLogger(AssessService.class);
    private final ChatClient chatClient;
    private final ChatClient deepseekClient;



    public static final String system_Prompt = """
            你是一个评估专家，需要根据三项评价指标对问答答案进行打分评估：内容全面性、视角多样性、整体质量。
            """;
    public static final String user_Prompt = """
            # 任务：
            根据三项评估标准对下面的答案进行打分，每项指标分值区间在1-10分，严格遵守下面分级打分细则。
            # 注意：
            打分里有必须引用答案原文内容进行作证，禁止空泛笼统的描述，所有解释要结合实际文本。
            # 指标介绍
            - 答案正确性
            待评估答案是否与正确答案表述的意思一致？
            - 内容全面性
            待评估答案能否完整覆盖问题所有提问角度与细节？
            - 视角多样性
            待评估答案是否与正确答案具有一致的回答角度、观点与见解，内容丰富度怎么样？
            - 整体质量
            结合前面三项项指标综合评估整体表现，兼顾内容质量与答案对原问题的实用价值。
            
            ## 打分分级标准
            1-2分：极差，该项缺陷突出，存在严重内容问题；
            3-4分：偏弱，多项关键内容缺失，问题明显；
            5-6分：中等，刚好满足基础作答要求，存在少量瑕疵；
            7-8分：优良，整体表现出色，仅存在细微不足；
            9-10分：优秀，该维度表现近乎完美，无明显缺陷。
            
            # 输入
            待评估问题：%s
            待评估答案：%s
            正确答案：%s
            
            # 输出
            返回严格的 JSON 格式，字符串内部**禁止使用双引号 "**，如果必须引用内容请使用单引号 '。
            
            按照上述四项标准完成打分，每项分数附带结合原文的详细说明。
            
            """;

    public AssessService(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                         @Qualifier("deepseekV4") @Autowired ChatClient deepseekClient) {
        this.chatClient = chatClient;
        this.deepseekClient = deepseekClient;
    }

    @Schema(description = "答案评估结果")
    public record AssessResult(
            @Schema(description = "答案正确性评估")
            EvaluationItem correctness,

            @Schema(description = "内容全面性评估")
            EvaluationItem contentComprehensiveness,

            @Schema(description = "视角多样性评估")
            EvaluationItem diversity,

            @Schema(description = "整体质量评估")
            EvaluationItem overall
    ) {
        // 嵌套的评分项：包含 score + explanation
        @Schema(description = "评估明细项")
        public record EvaluationItem(
                @Schema(description = "评分 1-10分")
                Integer score,

                @Schema(description = "评分说明/解释")
                String explanation
        ) {}
    }
    public AssessResult Assess(String query,String pred, String gold) {
        String user_prompt = user_Prompt.formatted(query,pred, gold);
        AssessResult result = deepseekClient.prompt()
                .user(user_prompt)
                .call()
                .entity(AssessResult.class);
        return result;
    }
}
