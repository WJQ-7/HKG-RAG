package com.bistu.hkgrag.llm.evaluation;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class EvaluationUtils {
    private static final JiebaSegmenter segmenter = new JiebaSegmenter();

    public EvaluationUtils() {
    }

    /**
     * 文本归一化：去除空白和标点
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", "")
                .replaceAll("[，。,.、;；:：！？!]", "")
                .trim();
    }

    /**
     * 词级分词：使用 jieba 分词器
     */
    public static List<String> tokenize(String s) {
        String text = normalize(s);
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        return segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
                .stream()
                .map(t -> t.word)
                .collect(Collectors.toList());
    }


    /**
     * EM分数：要求与标准答案完全一致
     */
    public static boolean exactMatch(String pred, String gold) {
        return normalize(pred).equals(normalize(gold));
    }

    /**
     * 召回率 Recall = 重叠词数 / 标准答案总词数
     * 衡量：标准答案里的内容，模型答案覆盖了多少
     */
    public static double recallScore(String pred, String gold) {
        List<String> predList = tokenize(pred);
        List<String> goldList = tokenize(gold);

        // 边界：标准答案为空 或 预测为空
        if (goldList.isEmpty() || predList.isEmpty()) {
            return goldList.isEmpty() ? 1.0 : 0.0;
        }

        Map<String, Integer> counter = new HashMap<>();
        for (String token : predList) {
            counter.put(token, counter.getOrDefault(token, 0) + 1);
        }

        int overlap = 0;
        for (String token : goldList) {
            if (counter.getOrDefault(token, 0) > 0) {
                overlap++;
                counter.put(token, counter.get(token) - 1);
            }
        }

        // 核心公式：Recall = 重叠数 / 标准答案总长度
        return (double) overlap / goldList.size();
    }



    /**
     * F1分数计算
     * @param pred 模型生成答案
     * @param gold 标准答案
     * @return F1分数
     */
    public static double f1Score(String pred, String gold) {
        List<String> predList = tokenize(pred);
        List<String> goldList = tokenize(gold);

        if (predList.isEmpty() || goldList.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counter = new HashMap<>();

        for (String token : predList) {
            counter.put(token,
                    counter.getOrDefault(token, 0) + 1);
        }

        int overlap = 0;

        for (String token : goldList) {

            if (counter.getOrDefault(token, 0) > 0) {

                overlap++;

                counter.put(
                        token,
                        counter.get(token) - 1
                );
            }
        }
        double precision =
                (double) overlap / predList.size();

        double recall =
                (double) overlap / goldList.size();

        if (precision + recall == 0) {
            return 0;
        }

        return 2 * precision * recall
                / (precision + recall);

    }

    private static int lcsLength(
            List<String> predictionTokens,
            List<String> goldTokens
    ) {

        int n = predictionTokens.size();
        int m = goldTokens.size();

        int[][] dp =
                new int[n + 1][m + 1];

        for (int i = 1; i <= n; i++) {

            for (int j = 1; j <= m; j++) {

                if (predictionTokens.get(i - 1)
                        .equals(goldTokens.get(j - 1))) {

                    dp[i][j] =
                            dp[i - 1][j - 1] + 1;

                } else {

                    dp[i][j] =
                            Math.max(
                                    dp[i - 1][j],
                                    dp[i][j - 1]
                            );
                }
            }
        }

        return dp[n][m];
    }

    /**
     * ROUGE-L(F1)
     */
    public static double rougeL(
            String prediction,
            String gold
    ) {

        List<String> predictionTokens = tokenize(prediction);
        List<String> goldTokens = tokenize(gold);

        if (predictionTokens.isEmpty()
                || goldTokens.isEmpty()) {

            return 0.0;
        }

        int lcs =
                lcsLength(predictionTokens, goldTokens);

        double precision =
                (double) lcs / predictionTokens.size();

        double recall =
                (double) lcs / goldTokens.size();

        if (precision + recall == 0) {
            return 0;
        }

        return 2 * precision * recall
                / (precision + recall);
    }


    /**
     * 计算三类评分的平均分，并分别返回
     *
     * @param results 评估结果列表
     * @return 包含三类平均分的 Map：contentComprehensiveness、empowerment、diversity、overall
     */
    public static Map<String, Double> llmScore(List<AssessService.AssessResult> results) {
        Map<String, Double> scoreMap = new HashMap<>();

        // 空值防护
        if (results == null || results.isEmpty()) {
            scoreMap.put("contentComprehensiveness", 0.0);
            scoreMap.put("empowerment", 0.0);
            scoreMap.put("diversity", 0.0);
            scoreMap.put("overall", 0.0);
            return scoreMap;
        }

        // 累加总分
        int totalComprehensive = 0;
        int totalEmpowerment = 0;
        int totalDiversity = 0;
        int totalOverall = 0;
        int count = results.size();

        for (AssessService.AssessResult result : results) {
            totalComprehensive += result.contentComprehensiveness().score();
            totalEmpowerment += result.empowerment().score();
            totalDiversity += result.diversity().score();
            totalOverall += result.overall().score();
        }


        // 计算每类均分（保留小数）
        double avgComprehensive = (double) totalComprehensive / count;
        double avgEmpower = (double) totalEmpowerment / count;
        double avgDiversity = (double) totalDiversity / count;
        double avgOverall = (double) totalOverall / count;

        // 存入结果并返回
        scoreMap.put("contentComprehensiveness", avgComprehensive);
        scoreMap.put("empowerment", avgEmpower);
        scoreMap.put("diversity", avgDiversity);
        scoreMap.put("overall", avgOverall);

        return scoreMap;
    }


    /**
     * 将各方法的 overall 指标写入对比表格
     */
    public static void writeComparisonTable(Map<String, Map<String, Double>> allScores, String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("==================== 四种 RAG 方法评估结果对比 ====================\n\n");

            String header = String.format("%-15s %10s %10s %10s %10s", "Method", "EM", "F1", "RougeL", "Recall");
            writer.write(header);
            writer.newLine();
            writer.write("----------------------------------------------------------------\n");

            for (Map.Entry<String, Map<String, Double>> entry : allScores.entrySet()) {
                Map<String, Double> s = entry.getValue();
                writer.write(String.format("%-15s %10.4f %10.4f %10.4f %10.4f",
                        entry.getKey(), s.get("EM"), s.get("F1"), s.get("RougeL"), s.get("Recall")));
                writer.newLine();
            }

            writer.newLine();
            writer.write("==================== 文件清单 ====================\n");
            for (String method : allScores.keySet()) {
                writer.write("  " + method + "_QA.jsonl       -> 原始问答记录\n");
                writer.write("  " + method + "_Detail.jsonl    -> 逐条评估详情\n");
                writer.write("  " + method + "_Summary.txt     -> 按类型汇总指标\n");
            }
            writer.write("  All_Methods_Comparison.txt -> 本文件（四种方法对比）\n");
        }
    }

}
