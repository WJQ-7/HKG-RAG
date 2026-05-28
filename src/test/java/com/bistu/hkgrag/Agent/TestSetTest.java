//package com.bistu.hkgrag.Agent;
//
//import com.bistu.hkgrag.dao.milvus.model.DoFile;
//import com.bistu.hkgrag.llm.agent.MultiAgent;
//import com.bistu.hkgrag.llm.controller.BaseLineService;
//import com.bistu.hkgrag.llm.model.agent.EvalRecord;
//import com.bistu.hkgrag.llm.model.agent.RagResult;
//import com.bistu.hkgrag.llm.model.agent.TestSet;
//import com.bistu.hkgrag.preprocess.utils.TextUtils;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.util.StringUtils;
//
//import java.io.BufferedWriter;
//import java.io.FileWriter;
//import java.util.*;
//import java.util.stream.Collectors;
//
//@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
//public class TestSetTest {
//    private final MultiAgent multiAgent;
//    private final BaseLineService baseLineService;
//
//
//    public TestSetTest(@Autowired MultiAgent multiAgent,
//                       @Autowired BaseLineService baseLineService) {
//        this.multiAgent = multiAgent;
//        this.baseLineService = baseLineService;
//    }
//
//    @Test
//    public void test_TestSet() {
//
//        String inputPath = "src/main/resources/data/test_set.jsonl";
//        String outputPath = "src/main/resources/data/result/baseline_test2.jsonl";
//       // String outputPath = "src/main/resources/data/result/MultiAgentWorkflow_test9.jsonl";
//        try {
//            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);
//
//            // 生成指标
//            Map<String, Double> emMap = new HashMap<>();
//            Map<String, Double> f1Map = new HashMap<>();
//            Map<String, Double> rougeMap = new HashMap<>();
//
//            // 检索指标
//            Map<String, Double> hit5Map = new HashMap<>();
//            Map<String, Double> recall5Map = new HashMap<>();
//
//            Map<String, Integer> countMap = new HashMap<>();
//
//            double totalEM = 0.0;
//            double totalF1 = 0.0;
//            double totalROUGE = 0.0;
//            double totalHit5 = 0.0;
//            double totalRecall5 = 0.0;
//            int totalCount = 0;
//
//            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath));
//            ObjectMapper mapper = new ObjectMapper();
//            int index = 0;
//            int total = testSetList.size();
//
//            for (TestSet t : testSetList) {
//                RagResult ragResult;
//                ragResult=multiAgent.MultiAgentWorkflow(t.getQuestion());
//                String pred = ragResult.getAnswer();
////                RagResult ragResult;
////                ragResult = baseLineService.baselineRAG(t.getQuestion());
////                String pred=ragResult.getAnswer();
//                String gold = t.getAnswer();
//                String type = t.getType().toString();
//
//                // 生成指标
//                boolean em = exactMatch(pred, gold);
//                double f1 = f1Score(pred, gold);
//                double rouge = rougeL(pred, gold);
//
//                // 检索指标 Hit@5 Recall@5
//                boolean hit5 = hitAtK(ragResult.getFiles(), t.getFileId(), 5);
//                double recall5 = recallAtK(ragResult.getFiles(), t.getFileId(), 5);
//
//
//                // ===== 单条记录写入 =====
//                EvalRecord record = new EvalRecord();
//                record.setFileId(t.getFileId());
//                record.setType(t.getType());
//                record.setQuestion(t.getQuestion());
//                record.setPredAnswer(pred);
//                record.setGoldAnswer(t.getAnswer());
//                record.setEm(em);
//                record.setF1(f1);
//                record.setRougeL(rouge);
//
//
//                writer.write(mapper.writeValueAsString(record));
//                writer.newLine();
//
//                // ===== 分类统计 =====
//                emMap.put(type, emMap.getOrDefault(type, 0.0) + (em ? 1 : 0));
//                f1Map.put(type, f1Map.getOrDefault(type, 0.0) + f1);
//                rougeMap.put(type, rougeMap.getOrDefault(type, 0.0) + rouge);
//                hit5Map.put(type, hit5Map.getOrDefault(type, 0.0) + (hit5 ? 1 : 0));
//                recall5Map.put(type, recall5Map.getOrDefault(type, 0.0) + recall5);
//                countMap.put(type, countMap.getOrDefault(type, 0) + 1);
//
//                // ===== 总体 =====
//                totalEM += (em ? 1 : 0);
//                totalF1 += f1;
//                totalROUGE += rouge;
//                totalHit5 += (hit5 ? 1 : 0);
//                totalRecall5 += recall5;
//                totalCount++;
//
//                // ===== 进度条 =====
//                index++;
//                if (index % 1 == 0) {
//                    double progress = (double) index / total * 100;
//                    System.out.printf("\r[Progress] %d/%d (%.2f%%)\n", index, total, progress);
//                }
//            }
//            writer.close();
//
//            BufferedWriter summaryWriter = new BufferedWriter(
//                    new FileWriter("src/main/resources/data/result/baselineResult_test9.txt")
//            );
//
//            summaryWriter.write("=========== RESULT ===========\n\n");
//
//            for (String type : countMap.keySet()) {
//                int cnt = countMap.get(type);
//                double emAvg = emMap.get(type) / cnt;
//                double f1Avg = f1Map.get(type) / cnt;
//                double rougeAvg = rougeMap.get(type) / cnt;
//                double hit5 = hit5Map.get(type) / cnt;
//                double recall5 = recall5Map.get(type) / cnt;
//
//                summaryWriter.write("Type: " + type + "Count: " + cnt + "\n");
//                summaryWriter.write(String.format("EM : %.4f\n", emAvg));
//                summaryWriter.write(String.format("F1 : %.4f\n", f1Avg));
//                summaryWriter.write(String.format("ROUGE-L  : %.4f\n", rougeAvg));
//                summaryWriter.write(String.format("Hit@5    : %.4f\n", hit5));
//                summaryWriter.write(String.format("Recall@5 : %.4f\n", recall5));
//                summaryWriter.write("---------------------------\n");
//            }
//
//            // overall
//            summaryWriter.write("\n=========== OVERALL ===========\n");
//            summaryWriter.write(String.format("测试集总数:  %d\n", totalCount));
//            summaryWriter.write(String.format("EM : %.4f\n", totalEM / totalCount));
//            summaryWriter.write(String.format("F1 : %.4f\n", totalF1 / totalCount));
//            summaryWriter.write(String.format("ROUGE-L  : %.4f\n", totalROUGE / totalCount));
//            summaryWriter.write(String.format("Hit@5    : %.4f\n", totalHit5 / totalCount));
//            summaryWriter.write(String.format("Recall@5 : %.4f\n", totalRecall5 / totalCount));
//            summaryWriter.close();
//
//            System.out.println("评估完成，结果已写入文件！");
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.out.println("评估测试集失败" + e.getMessage());
//        }
//    }
//
//
//    public String normalize(String s) {
//        if (s == null) return "";
//        return s.replaceAll("\\s+", "")
//                .replaceAll("[，。,.、;；:：！？!]", "")
//                .trim();
//    }
//
//    public boolean exactMatch(String pred, String gold) {
//        return normalize(pred).equals(normalize(gold));
//    }
//
//    public double f1Score(String pred, String gold) {
//
//        List<String> predList = Arrays.asList(normalize(pred).split(""));
//        List<String> goldList = Arrays.asList(normalize(gold).split(""));
//
//        Map<String, Integer> map = new HashMap<>();
//
//        for (String t : predList) {
//            map.put(t, map.getOrDefault(t, 0) + 1);
//        }
//
//        int overlap = 0;
//
//        for (String t : goldList) {
//            if (map.getOrDefault(t, 0) > 0) {
//                overlap++;
//                map.put(t, map.get(t) - 1);
//            }
//        }
//
//        double precision = (double) overlap / predList.size();
//        double recall = (double) overlap / goldList.size();
//
//        if (precision + recall == 0) return 0;
//
//        return 2 * precision * recall / (precision + recall);
//    }
//
//    public int lcsLength(List<String> predictionTokens, List<String> goldTokens) {
//
//        int n = predictionTokens.size();
//        int m = goldTokens.size();
//
//        int[][] dp = new int[n + 1][m + 1];
//
//        for (int i = 1; i <= n; i++) {
//            for (int j = 1; j <= m; j++) {
//
//                String predToken = predictionTokens.get(i - 1);
//                String goldToken = goldTokens.get(j - 1);
//
//                if (predToken.equals(goldToken)) {
//                    dp[i][j] = dp[i - 1][j - 1] + 1;
//                } else {
//                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
//                }
//            }
//        }
//
//        return dp[n][m];
//    }
//
//    public double rougeL(String prediction, String gold) {
//
//        List<String> predictionTokens = Arrays.asList(normalize(prediction).split(""));
//        List<String> goldTokens = Arrays.asList(normalize(gold).split(""));
//
//        if (predictionTokens.isEmpty() || goldTokens.isEmpty()) {
//            return 0.0;
//        }
//
//        int lcs = lcsLength(predictionTokens, goldTokens);
//
//        double precision = (double) lcs / predictionTokens.size();
//        double recall = (double) lcs / goldTokens.size();
//
//        if (precision + recall == 0) {
//            return 0.0;
//        }
//
//        return 2 * precision * recall / (precision + recall);
//    }
//
//    // ================== 检索评估指标 ==================
//    public boolean hitAtK(List<DoFile> retrievedDocList, String goldenDocId, int k) {
//        if (retrievedDocList == null || retrievedDocList.isEmpty() ||
//                !StringUtils.hasText(goldenDocId)) {
//            return false;
//        }
//
//        Set<String> goldenSet = Arrays.stream(goldenDocId.split(","))
//                .map(String::trim)
//                .collect(Collectors.toSet());
//
//        // 取前k个检索结果
//        int topK = Math.min(k, retrievedDocList.size());
//        Set<String> topKSet = new HashSet<>();
//        for (int i = 0; i < topK; i++) {
//            DoFile file = retrievedDocList.get(i);
//            if (file != null && StringUtils.hasText(file.getFileId())) {
//                topKSet.add(file.getFileId());
//            }
//        }
//        // 必须全部包含
//        return topKSet.containsAll(goldenSet);
//    }
//
//    /**
//     * 通用 Recall@k
//     * 前 k 个结果命中 = 1.0，否则 0.0
//     */
//    public double recallAtK(List<DoFile> retrievedDocList, String goldenDocId, int k) {
//        if (retrievedDocList == null || retrievedDocList.isEmpty() || !StringUtils.hasText(goldenDocId)) {
//            return 0.0;
//        }
//
//        // 1. 解析所有正确ID
//        Set<String> goldenSet = Arrays.stream(goldenDocId.split(","))
//                .map(String::trim)
//                .filter(StringUtils::hasText)
//                .collect(Collectors.toSet());
//
//        // 2. 取出TopK的docId
//        int topK = Math.min(k, retrievedDocList.size());
//        Set<String> topKSet = new HashSet<>();
//        for (int i = 0; i < topK; i++) {
//            DoFile file = retrievedDocList.get(i);
//            if (file != null && StringUtils.hasText(file.getFileId())) {
//                topKSet.add(file.getFileId());
//            }
//        }
//
//        // 3. 计算命中了几个正确ID
//        double hitCount = 0;
//        for (String gid : goldenSet) {
//            if (topKSet.contains(gid)) {
//                hitCount++;
//            }
//        }
//
//        // 4. 标准召回率：命中数 / 总数
//        return hitCount / goldenSet.size();
//    }
//}
