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
    public static final String user_Prompt = """
            # 任务：
            你的任务是依据四项标准对下方回答进行评估。每项标准打分区间为 1 至 10 分，打分需严格遵循详细评分细则。
            # 注意：
            打分里有必须引用答案原文内容进行作证，禁止空泛笼统的描述，所有解释要结合实际文本。
            # 指标介绍
            - 内容全面性 contentComprehensiveness
            该回答提供的细节是否完整覆盖问题的全部层面与细分要点？
            - 指导性 empowerment
            回答能否有效帮助读者理解相关主题，并支撑读者做出客观、合理的判断？
            - 视角多样性 diversity
            回答能否有效帮助读者理解相关主题，并支撑读者做出客观、合理的判断？
            - 整体质量 overall
            结合前面三项项指标综合评估整体表现，兼顾内容质量与答案对原问题的实用价值。
            
            ## 打分分级标准
            1-2分：极差，该项缺陷突出，存在严重内容问题；
            3-4分：偏弱，多项关键内容缺失，问题明显；
            5-6分：中等，刚好满足基础作答要求，存在少量瑕疵；
            7-8分：优良，整体表现出色，仅存在细微不足；
            9-10分：优秀，该维度表现极佳，无明显缺陷。
            
            # 输入
            待评估问题：%s
            待评估答案：%s
            正确答案：%s
            
            请按照上述四项标准完成打分评估，并结合原文为每项标准撰写详细说明。
            输出格式严格遵循以下 JSON 结构：
            {
            "contentComprehensiveness": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "empowerment": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "diversity": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "overall": {
            "score": "[1-10 之间的数字]",
            "explanation": "[结合前三项标准，总结该回答综合表现优劣]"
            }
            }
            """;

    public AssessService(@Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                         @Qualifier("deepseekV4") @Autowired ChatClient deepseekClient) {
        this.chatClient = chatClient;
        this.deepseekClient = deepseekClient;
    }

    @Schema(description = "答案评估结果")
    public record AssessResult(
            @Schema(description = "内容全面性评估")
            EvaluationItem contentComprehensiveness,

            @Schema(description = "答案正确性评估")
            EvaluationItem empowerment,

            @Schema(description = "多样性评估")
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
