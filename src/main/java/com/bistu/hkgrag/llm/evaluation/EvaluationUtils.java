package com.bistu.hkgrag.llm.evaluation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvaluationUtils {
    public EvaluationUtils() {
    }

    /**
     * 文本归一化（文本分词）
     * @param s 待分词文本
     * @return 分词后文本
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
     * EM分数：要求与标准答案完全一致
     */
    public static boolean exactMatch(String pred, String gold) {
        return normalize(pred).equals(normalize(gold));
    }

    /**
     * 召回率 Recall = 重叠字符数 / 标准答案总字符数
     * 衡量：标准答案里的内容，模型答案覆盖了多少
     */
    public static double recallScore(String pred, String gold) {
        List<String> predList = Arrays.asList(normalize(pred).split(""));
        List<String> goldList = Arrays.asList(normalize(gold).split(""));

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
        List<String> predList =
                Arrays.asList(normalize(pred).split(""));

        List<String> goldList =
                Arrays.asList(normalize(gold).split(""));
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

        List<String> predictionTokens =
                Arrays.asList(normalize(prediction).split(""));

        List<String> goldTokens =
                Arrays.asList(normalize(gold).split(""));

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
     * @return 包含三类平均分的 Map：contentComprehensiveness、diversity、overall
     */
    public static Map<String, Double> llmScore(List<AssessService.AssessResult> results) {
        Map<String, Double> scoreMap = new HashMap<>();

        // 空值防护
        if (results == null || results.isEmpty()) {
            scoreMap.put("correctness", 0.0);
            scoreMap.put("contentComprehensiveness", 0.0);
            scoreMap.put("diversity", 0.0);
            scoreMap.put("overall", 0.0);
            return scoreMap;
        }

        // 累加总分
        int totalCorrect = 0;
        int totalContent = 0;
        int totalDiversity = 0;
        int totalOverall = 0;
        int count = results.size();

        for (AssessService.AssessResult result : results) {
            totalCorrect += result.correctness().score();
            totalContent += result.contentComprehensiveness().score();
            totalDiversity += result.diversity().score();
            totalOverall += result.overall().score();
        }

        // 计算每类均分（保留小数）
        double avgCorrect = (double) totalCorrect / count;
        double avgContent = (double) totalContent / count;
        double avgDiversity = (double) totalDiversity / count;
        double avgOverall = (double) totalOverall / count;

        // 存入结果并返回
        scoreMap.put("correctness", avgCorrect);
        scoreMap.put("contentComprehensiveness", avgContent);
        scoreMap.put("diversity", avgDiversity);
        scoreMap.put("overall", avgOverall);

        return scoreMap;
    }

}
