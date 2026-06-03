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

}
