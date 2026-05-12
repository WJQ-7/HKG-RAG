package com.bistu.hkgrag.Agent;

import com.bistu.hkgrag.llm.agent.MultiAgent;
import com.bistu.hkgrag.llm.controller.BaseLineService;
import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class TestSetTest {
    private final MultiAgent multiAgent;
    private final BaseLineService baseLineService;


    public TestSetTest(@Autowired MultiAgent multiAgent,
                       @Autowired BaseLineService baseLineService) {
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
    }

    @Test
    public void test_TestSet() {

        String inputPath = "src/main/resources/data/test_set.jsonl";
       // String outputPath = "src/main/resources/data/result/baseline.jsonl";
        String outputPath = "src/main/resources/data/result/MultiAgentWorkflow_test4.jsonl";
        try {
            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);

            Map<String, Double> emMap = new HashMap<>();
            Map<String, Double> f1Map = new HashMap<>();
            Map<String, Double> rougeMap = new HashMap<>();
            Map<String, Integer> countMap = new HashMap<>();


            double totalEM = 0.0;
            double totalF1 = 0.0;
            double totalROUGE = 0.0;
            int totalCount = 0;

            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath));
            ObjectMapper mapper = new ObjectMapper();
            int index = 0;
            int total = testSetList.size();

            for (TestSet t : testSetList) {
                String pred = multiAgent.MultiAgentWorkflow(t.getQuestion());
              //  String pred = baseLineService.baselineRAG(t.getQuestion());
                String gold = t.getAnswer();
                String type = t.getType().toString();

                boolean em = exactMatch(pred, gold);
                double f1 = f1Score(pred, gold);
                double rouge = rougeL(pred, gold);

                // ===== 单条记录写入 =====
                EvalRecord record = new EvalRecord();
                record.setFileId(t.getFileId());
                record.setType(t.getType());
                record.setQuestion(t.getQuestion());
                record.setPredAnswer(pred);
                record.setGoldAnswer(t.getAnswer());
                record.setEm(em);
                record.setF1(f1);
                record.setRougeL(rouge);


                writer.write(mapper.writeValueAsString(record));
                writer.newLine();

                // ===== 分类统计 =====
                emMap.put(type, emMap.getOrDefault(type, 0.0) + (em ? 1 : 0));
                f1Map.put(type, f1Map.getOrDefault(type, 0.0) + f1);
                rougeMap.put(type, rougeMap.getOrDefault(type, 0.0) + rouge);
                countMap.put(type, countMap.getOrDefault(type, 0) + 1);

                // ===== 总体 =====
                totalEM += (em ? 1 : 0);
                totalF1 += f1;
                totalROUGE += rouge;
                totalCount++;


                // ===== 进度条 =====
                index++;
                if (index % 1 == 0) {
                    double progress = (double) index / total * 100;
                    System.out.printf("\r[Progress] %d/%d (%.2f%%)\n", index, total, progress);
                }
            }
            writer.close();

            BufferedWriter summaryWriter = new BufferedWriter(
                    new FileWriter("src/main/resources/data/result/MultiAgentWorkflowResult_test4.txt")
            );

            summaryWriter.write("=========== RESULT ===========\n\n");

            for (String type : countMap.keySet()) {

                int cnt = countMap.get(type);
                double emAvg = emMap.get(type) / cnt;
                double f1Avg = f1Map.get(type) / cnt;
                double rougeAvg = rougeMap.get(type) / cnt;

                summaryWriter.write("Type: " + type + "Count: " + cnt + "\n");
                summaryWriter.write(String.format("EM : %.4f\n", emAvg));
                summaryWriter.write(String.format("F1 : %.4f\n", f1Avg));
                summaryWriter.write(String.format("ROUGE-L  : %.4f\n", rougeAvg));
                summaryWriter.write("---------------------------\n");
            }

            // overall
            summaryWriter.write("\n=========== OVERALL ===========\n");
            summaryWriter.write(String.format("测试集总数:  %d\n", totalCount));
            summaryWriter.write(String.format("EM : %.4f\n", totalEM / totalCount));
            summaryWriter.write(String.format("F1 : %.4f\n", totalF1 / totalCount));
            summaryWriter.write(String.format("ROUGE-L  : %.4f\n", totalROUGE / totalCount));
            summaryWriter.close();

            System.out.println("评估完成，结果已写入文件！");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("评估测试集失败" + e.getMessage());
        }
    }


    public String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "")
                .replaceAll("[，。,.、;；:：！？!]", "")
                .trim();
    }

    public boolean exactMatch(String pred, String gold) {
        return normalize(pred).equals(normalize(gold));
    }

    public double f1Score(String pred, String gold) {

        List<String> predList = Arrays.asList(normalize(pred).split(""));
        List<String> goldList = Arrays.asList(normalize(gold).split(""));

        Map<String, Integer> map = new HashMap<>();

        for (String t : predList) {
            map.put(t, map.getOrDefault(t, 0) + 1);
        }

        int overlap = 0;

        for (String t : goldList) {
            if (map.getOrDefault(t, 0) > 0) {
                overlap++;
                map.put(t, map.get(t) - 1);
            }
        }

        double precision = (double) overlap / predList.size();
        double recall = (double) overlap / goldList.size();

        if (precision + recall == 0) return 0;

        return 2 * precision * recall / (precision + recall);
    }

    public int lcsLength(List<String> predictionTokens, List<String> goldTokens) {

        int n = predictionTokens.size();
        int m = goldTokens.size();

        int[][] dp = new int[n + 1][m + 1];

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {

                String predToken = predictionTokens.get(i - 1);
                String goldToken = goldTokens.get(j - 1);

                if (predToken.equals(goldToken)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        return dp[n][m];
    }

    public double rougeL(String prediction, String gold) {

        List<String> predictionTokens = Arrays.asList(normalize(prediction).split(""));
        List<String> goldTokens = Arrays.asList(normalize(gold).split(""));

        if (predictionTokens.isEmpty() || goldTokens.isEmpty()) {
            return 0.0;
        }

        int lcs = lcsLength(predictionTokens, goldTokens);

        double precision = (double) lcs / predictionTokens.size();
        double recall = (double) lcs / goldTokens.size();

        if (precision + recall == 0) {
            return 0.0;
        }

        return 2 * precision * recall / (precision + recall);
    }

}
