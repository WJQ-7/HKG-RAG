package com.bistu.hkgrag.llm.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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




@Service
public class EvaluationAgent {
    private static Logger log = LoggerFactory.getLogger(EvaluationAgent.class);
    private final EvidenceStore evidenceStore;
    private ChatClient chatClient;

    private static final String Agent_Evaluation = """
            # 角色：答案评估专家，判断初始答案能否回答用户问题，失败时给出结构化诊断。
            
            ## 核心任务：
            1. 判断答案是否能回答用户问题（PASS/FAIL）
            2. FAIL时必须给出 diagnosis 诊断信息，指导下一轮调整策略
            
            ## 诊断规则：
            
            ### 1. 多跳问题（使用 getEntityTriples 工具）
            - 关系选择与问题语义不匹配 → WRONG_RELATION_PATH
            - 扩展跳数不够 → INSUFFICIENT_EXPANSION
            
            ### 2. 抽象类问题（使用 searchFileByTopic 工具）
            - 主题不合适 → WRONG_TOPICS
            - 需要更精准表述 → NEED_QUESTION_REWRITE
            
            ### 3. 问题类型错误
            - 问题分析智能体定义的类型与实际需求不匹配 → WRONG_QUERY_TYPE
            
            suggestionDetail 根据 problemType 填写对应内容（如关系语义描述、建议主题、改写问题、正确类型等）。
            nextRoundStrategy 必须给出下一轮具体可执行的策略。
            
            # 输入：
            用户问题：%s
            当前轮次的问题分析结果：%s
            初始答案：%s
            答案依据（工具检索结果）：
            %s
            
            # 输出JSON（单行，无额外文字）：
            {"success":"PASS或FAIL","reason":"理由","diagnosis":{"problemType":"WRONG_RELATION_PATH|INSUFFICIENT_EXPANSION|WRONG_TOPICS|NEED_QUESTION_REWRITE|WRONG_QUERY_TYPE","detail":"诊断说明","suggestionDetail":"修改建议","nextRoundStrategy":"策略建议"}}
            
            示例：
            PASS：{"success":"PASS","reason":"答案准确完整回答了问题","diagnosis":null}
            
            WRONG_RELATION_PATH：{"success":"FAIL","reason":"问题问部门参与的涉密项目，但选了'指导学生'关系，与问题无关","diagnosis":{"problemType":"WRONG_RELATION_PATH","detail":"选了'指导学生'，但核心是部门→涉密项目，应从实际关系中语义匹配归属关系","suggestionDetail":"建议关系：语义表示归属/所属的关系、语义表示参与项目的关系","nextRoundStrategy":"更换语义方向：先获取部门，再从部门关系中匹配参与项目的关系"}}
            
            WRONG_TOPICS：{"success":"FAIL","reason":"主题'项目风险管理'检索无结果，过于狭窄","diagnosis":{"problemType":"WRONG_TOPICS","detail":"当前主题无匹配","suggestionDetail":"建议主题：项目安全、年度报告、风险排查","nextRoundStrategy":"更换主题：用['项目安全','年度报告']检索，辅以searchFileByQuery"}}
            
            WRONG_QUERY_TYPE：{"success":"FAIL","reason":"问题分析智能体定义为MULTI_HOP，但张三的电话是具体细节问题，类型定义错误","diagnosis":{"problemType":"WRONG_QUERY_TYPE","detail":"具体事实查询不需要多跳推理，问题分析智能体的queryType定义错误","suggestionDetail":"正确类型：SPECIFIC_DETAIL","nextRoundStrategy":"重新分类为SPECIFIC_DETAIL，用searchFileByQuery检索"}}
            
            INSUFFICIENT_EXPANSION：{"success":"FAIL","reason":"只扩展到学生层级，未获取论文信息","diagnosis":{"problemType":"INSUFFICIENT_EXPANSION","detail":"完成教师→学生一跳，还需学生→论文第二跳","suggestionDetail":"建议关系：语义表示发表/撰写的关系","nextRoundStrategy":"继续扩展：对每个学生节点，从实际关系中匹配表示发表的关系"}}
            """;

    public EvaluationAgent( @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
                            @Autowired EvidenceStore evidenceStore) {
        this.chatClient = chatClient;
        this.evidenceStore = evidenceStore;
    }

    @Schema(description = "问答评估结果")
    public record EvaluationResult(
            @JsonProperty(value = "success",required = true)
            @Schema(description = "当前答案能否成功回答用户提问", requiredMode = Schema.RequiredMode.REQUIRED)
            Evaluation success,

            @JsonProperty(value = "reason",required = true)
            @Schema(description = "答案通过/不通过的理由", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonPropertyDescription("答案通过或不通过的理由是什么")
            String reason,

            @JsonProperty(value = "diagnosis")
            @Schema(description = "失败诊断（仅FAIL时填写），用于指导下一轮问题分析智能体调整策略")
            Diagnosis diagnosis
    ) {
        public enum Evaluation {
            PASS, FAIL
        }

        /**
         * 结构化诊断信息，指导问题分析智能体下一轮调整策略
         */
        public enum ProblemType {
            WRONG_RELATION_PATH, INSUFFICIENT_EXPANSION, WRONG_TOPICS, NEED_QUESTION_REWRITE, WRONG_QUERY_TYPE
        }

        @Schema(description = "失败诊断信息")
        public record Diagnosis(
                @JsonProperty(value = "problemType", required = true)
                @Schema(description = "诊断出的根本问题类型")
                ProblemType problemType,

                @JsonProperty(value = "detail", required = true)
                @Schema(description = "具体诊断说明")
                String detail,

                @JsonProperty(value = "suggestionDetail")
                @Schema(description = "修改建议：根据problemType填写对应内容，如关系语义描述、建议主题、改写问题、正确类型等")
                String suggestionDetail,

                @JsonProperty(value = "nextRoundStrategy", required = true)
                @Schema(description = "下一轮应优先采取的策略建议")
                String nextRoundStrategy
        ) {}
    }

    /**
     * 答案评估智能体对用户问题与生成得初始答案进行评估
     * @param question 用户问题
     * @param plan 问题分析智能体的检索计划（queryType、keywords、topics）
     * @param answerResult 意图解析智能体的返回结果
     * @return
     */
    public EvaluationResult evaluate(String question, QuestionAnalysisAgent.RetrievalPlanResponse plan, IntentAgent.AnswerResult answerResult) {
        String evidence=Utils.buildEvidence(answerResult,evidenceStore);
        String AnswerEvaluate_Prompt=String.format(Agent_Evaluation,question,plan.toString(),answerResult.finalAnswer(),evidence);
        ChatOptions options = OpenAiChatOptions.builder()
                .responseFormat(
                        ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build()
                )
                .temperature(0.1)
                .build();
        EvaluationResult evaluationResult=chatClient.prompt()
                .user(AnswerEvaluate_Prompt)
                .options(options)
                .call()
                .entity(EvaluationResult.class);
        return evaluationResult;
    }

}
