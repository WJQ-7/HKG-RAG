package com.bistu.hkgrag.llm.agent;

import java.util.List;

public class Utils {
    public static String buildHistory(List<TestMultiAgent.Generation> generations) {
        if (generations == null || generations.isEmpty()) {
            return "暂无历史执行记录";
        }
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < generations.size(); i++) {

            TestMultiAgent.Generation g = generations.get(i);

            sb.append("===== 第")
                    .append(i + 1)
                    .append("轮 =====\n");

            sb.append("检索计划:\n")
                    .append(g.retrievalPlan())
                    .append("\n");

            sb.append("【工具调用记录】\n");

            if (g.answerResult() != null
                    && g.answerResult().allToolCalls() != null
                    && !g.answerResult().allToolCalls().isEmpty()) {

                for (IntentAgent.ToolCallTrace trace :
                        g.answerResult().allToolCalls()) {

                    sb.append("工具名称: ")
                            .append(trace.toolName())
                            .append("\n");

                    sb.append("输入参数: ")
                            .append(trace.input())
                            .append("\n");

//                    sb.append("检索结果摘要: ")
//                            .append(trace.retrievedContent())
//                            .append("\n");
                }
            } else {
                sb.append("无工具调用记录\n\n");
            }

            sb.append("生成答案:\n")
                    .append(g.answerResult().finalAnswer())
                    .append("\n");

            sb.append("评估结果:\n")
                    .append(g.evaluationResult().success())
                    .append("\n");

            sb.append("失败原因:\n")
                    .append(g.evaluationResult().reason())
                    .append("\n");

            sb.append("改进建议:\n")
                    .append(g.evaluationResult().suggestion())
                    .append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 用于评估智能体查看检索依据
     */
    public static String buildEvidence(IntentAgent.AnswerResult answerResult,EvidenceStore evidenceStore) {

        if (answerResult == null
                || answerResult.allToolCalls() == null
                || answerResult.allToolCalls().isEmpty()) {

            return "无检索依据";
        }

        StringBuilder sb = new StringBuilder();

        for (IntentAgent.ToolCallTrace trace :
                answerResult.allToolCalls()) {

            sb.append("====================================\n");

            sb.append("工具名称：")
                    .append(trace.toolName())
                    .append("\n");

            sb.append("输入参数：")
                    .append(trace.input())
                    .append("\n");

            String evidenceContent =
                    evidenceStore.get(trace.evidenceId());

            sb.append("回答依据：\n")
                    .append(
                            evidenceContent == null
                                    ? "证据不存在"
                                    : evidenceContent
                    )
                    .append("\n\n");
        }

        return sb.toString();
    }
}
