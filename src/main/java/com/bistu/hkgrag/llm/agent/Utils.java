package com.bistu.hkgrag.llm.agent;

import java.util.List;

public class Utils {
    public static String buildHistory(List<MultiAgent.Generation> generations) {
        if (generations == null || generations.isEmpty()) {
            return "暂无历史执行记录";
        }
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < generations.size(); i++) {

            MultiAgent.Generation g = generations.get(i);

            sb.append("===== 第")
                    .append(i + 1)
                    .append("轮 =====\n");

            sb.append("检索计划:\n")
                    .append(g.retrievalPlan())
                    .append("\n");

            sb.append("【工具调用记录】\n");

            if (g.answerResult() != null
                    && g.answerResult().toolCalls() != null
                    && !g.answerResult().toolCalls().isEmpty()) {

                for (IntentAgent.ToolCallTrace trace :
                        g.answerResult().toolCalls()) {

                    sb.append("工具名称: ")
                            .append(trace.toolName())
                            .append("\n");

                    sb.append("输入参数: ")
                            .append(trace.input())
                            .append("\n");

                    sb.append("检索结果摘要: ")
                            .append(trace.summary())
                            .append("\n");
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

            // 传递结构化诊断信息
            if (g.evaluationResult().diagnosis() != null) {
                EvaluationAgent.EvaluationResult.Diagnosis d = g.evaluationResult().diagnosis();
                sb.append("【诊断信息-供问题分析智能体参考】\n");
                sb.append("根本问题: ").append(d.problemType()).append("\n");
                sb.append("诊断详情: ").append(d.detail()).append("\n");
                if (d.suggestionDetail() != null && !d.suggestionDetail().isEmpty()) {
                    sb.append("修改建议: ").append(d.suggestionDetail()).append("\n");
                }
                sb.append("下轮策略: ").append(d.nextRoundStrategy()).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 用于评估智能体查看检索依据
     * 仅取最后一轮工具调用的检索结果，避免多轮累积导致token超限
     */
    public static String buildEvidence(IntentAgent.AnswerResult answerResult,EvidenceStore evidenceStore) {

        if (answerResult == null
                || answerResult.toolCalls() == null
                || answerResult.toolCalls().isEmpty()) {

            return "无检索依据";
        }

        // 仅取最后一个工具调用的结果
        IntentAgent.ToolCallTrace trace = answerResult.toolCalls()
                .get(answerResult.toolCalls().size() - 1);

        StringBuilder sb = new StringBuilder();

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

        return sb.toString();
    }
}
