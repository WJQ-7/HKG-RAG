package com.bistu.hkgrag.llm.evaluation;

import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.*;

public class Evaluator {
    private final static Logger log = LoggerFactory.getLogger(Evaluator.class);

    /**
     * 一次遍历算出的汇总指标，消除外部重复计算
     */
    public record EvalSummary(
            int totalCount,                                 // 总样本数
            Map<String, Double> overall,                    // 整体: EM/F1/RougeL/Recall
            Map<String, TypeScore> byType                   // 按类型: type -> {count, EM, F1, RougeL, Recall}
    ) {}

    public record TypeScore(int count, Map<String, Double> scores) {}

    /**
     * 单条评估：计算所有指标并写回 record
     */
    public static EvalRecord evaluateOne(EvalRecord record) {
        record.setEm(EvaluationUtils.exactMatch(record.getPredAnswer(), record.getGoldAnswer()));
        record.setF1(EvaluationUtils.f1Score(record.getPredAnswer(), record.getGoldAnswer()));
        record.setRougeL(EvaluationUtils.rougeL(record.getPredAnswer(), record.getGoldAnswer()));
        record.setRecall(EvaluationUtils.recallScore(record.getPredAnswer(), record.getGoldAnswer()));
        return record;
    }

    /**
     * 对所有记录逐条评估，一次遍历生成汇总（消除重复遍历）
     */
    public static EvalSummary evaluateAll(List<EvalRecord> records) {
        Map<String, Integer> countByType = new HashMap<>();
        Map<String, double[]> sumByType = new HashMap<>(); // [EM, F1, RougeL, Recall]

        double totalEM = 0, totalF1 = 0, totalRougeL = 0, totalRecall = 0;

        for (EvalRecord r : records) {
            evaluateOne(r);

            String type = r.getType().toString();
            countByType.merge(type, 1, Integer::sum);
            double[] sums = sumByType.computeIfAbsent(type, k -> new double[4]);
            sums[0] += r.isEm() ? 1 : 0;
            sums[1] += r.getF1();
            sums[2] += r.getRougeL();
            sums[3] += r.getRecall();

            totalEM += r.isEm() ? 1 : 0;
            totalF1 += r.getF1();
            totalRougeL += r.getRougeL();
            totalRecall += r.getRecall();
        }

        int n = records.size();

        Map<String, Double> overall = buildScoreMap(totalEM / n, totalF1 / n, totalRougeL / n, totalRecall / n);

        Map<String, TypeScore> byType = new LinkedHashMap<>();
        for (String type : countByType.keySet()) {
            int cnt = countByType.get(type);
            double[] s = sumByType.get(type);
            byType.put(type, new TypeScore(cnt, buildScoreMap(s[0] / cnt, s[1] / cnt, s[2] / cnt, s[3] / cnt)));
        }

        return new EvalSummary(n, overall, byType);
    }

    private static Map<String, Double> buildScoreMap(double em, double f1, double rougeL, double recall) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("EM", em);
        m.put("F1", f1);
        m.put("RougeL", rougeL);
        m.put("Recall", recall);
        return m;
    }

    // ==================== 写入文件 ====================

    public static void writeDetailResult(List<EvalRecord> records, String outputPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            for (EvalRecord r : records) {
                writer.write(mapper.writeValueAsString(r));
                writer.newLine();
            }
        }
    }

    public static void writeSummaryResult(EvalSummary summary, String outputPath) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("=========== RESULT ===========\n\n");

            for (Map.Entry<String, TypeScore> entry : summary.byType().entrySet()) {
                String type = entry.getKey();
                TypeScore ts = entry.getValue();
                Map<String, Double> s = ts.scores();
                writer.write(String.format("Type : %s Count: %d\n", type, ts.count()));
                writer.write(String.format("EM : %.4f\n", s.get("EM")));
                writer.write(String.format("F1 : %.4f\n", s.get("F1")));
                writer.write(String.format("ROUGE-L : %.4f\n", s.get("RougeL")));
                writer.write(String.format("RECALL : %.4f\n", s.get("Recall")));
                writer.write("---------------------------\n");
            }

            writer.write("\n=========== OVERALL ===========\n");
            writer.write(String.format("测试集总数: %d\n", summary.totalCount()));
            writer.write(String.format("EM : %.4f\n", summary.overall().get("EM")));
            writer.write(String.format("F1 : %.4f\n", summary.overall().get("F1")));
            writer.write(String.format("ROUGE-L : %.4f\n", summary.overall().get("RougeL")));
            writer.write(String.format("RECALL : %.4f\n", summary.overall().get("Recall")));
        }
    }

    // ==================== 总入口 ====================

    /**
     * 评估总方法：逐条评估 → 写详细文件 → 写汇总文件
     * @return EvalSummary 供外部复用（消除重复计算）
     */
    public static EvalSummary evaluate(List<EvalRecord> records,
                                       String detailOutputPath,
                                       String summaryOutputPath) {
        log.info("待评估[{}]条数据", records.size());
        try {
            EvalSummary summary = evaluateAll(records);
            log.info("所有记录评估完成");

            writeDetailResult(records, detailOutputPath);
            log.info("详细评估结果已写入: {}", detailOutputPath);

            writeSummaryResult(summary, summaryOutputPath);
            log.info("汇总评估结果已写入: {}", summaryOutputPath);
            log.info("评估完成");
            return summary;

        } catch (Exception e) {
            log.error("评估失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
