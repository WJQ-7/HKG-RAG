package com.bistu.hkgrag.Evaluation;

import com.bistu.hkgrag.llm.agent.MultiAgent;
import com.bistu.hkgrag.llm.agent.TestAgent;
import com.bistu.hkgrag.llm.evaluation.AssessService;
import com.bistu.hkgrag.llm.evaluation.EvaluationUtils;
import com.bistu.hkgrag.llm.evaluation.Evaluator;
import com.bistu.hkgrag.llm.evaluation.Evaluator.EvalSummary;
import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.service.BaseLineService;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@SpringBootTest
public class Evaluation {
    private static final Logger log = LoggerFactory.getLogger(Evaluation.class);
    private final MultiAgent multiAgent;
    private final BaseLineService baseLineService;
    private final AssessService assessService;
    private final TestAgent testAgent;

    public Evaluation(@Autowired MultiAgent multiAgent,
                      @Autowired BaseLineService baseLineService,
                      @Autowired AssessService assessService,
                      @Autowired TestAgent testAgent) {
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
        this.assessService = assessService;
        this.testAgent = testAgent;
    }

    @Test
    @Order(1)
    public void testSet() {
        String inputPath = "src/main/resources/data/datasets/Bistu/qa.jsonl";
        String outputPath = "src/main/resources/data/datasets/Bistu/HKGRAG-mix_QA.jsonl";
        try {
            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);
            List<EvalRecord> records = new ArrayList<>();
            log.info("待测试{}个问题", testSetList.size());
            for (int i = 0; i < testSetList.size(); i++) {
                TestSet t = testSetList.get(i);
             //  String pred=testAgent.test_Agent(t.getQuestion());
                String pred= multiAgent.testMultiAgent(t.getQuestion());
              //  String pred = baseLineService.naiveRAG(t.getQuestion()).getAnswer();
                EvalRecord record = new EvalRecord();
                record.setFileId(t.getFileId());
                record.setType(t.getType());
                record.setQuestion(t.getQuestion());
                record.setPredAnswer(pred);
                record.setGoldAnswer(t.getAnswer());
                records.add(record);
                log.info("处理进度：{}/{}", i + 1, testSetList.size());
            }
            TextUtils.writeJsonl(outputPath, records);
        } catch (Exception e) {
            log.error("数据集测试失败: {}", e.getMessage(), e);
        }
    }

    @Test
    @Order(2)
    public void evaluation() {
        try {
            String inputPath = "src/main/resources/data/result/HKGRAG_QA.jsonl";
            String detailOutputPath = "src/main/resources/data/result/evaluation/HKGRAG-v2-1_Detail.jsonl";
            String summaryOutputPath = "src/main/resources/data/result/evaluation/HKGRAG-v2-1_Summary.txt";
            List<EvalRecord> evalRecords = TextUtils.readJsonlToList(inputPath, EvalRecord.class);
            EvalSummary summary = Evaluator.evaluate(evalRecords, detailOutputPath, summaryOutputPath);
            Assertions.assertNotNull(summary);
        } catch (Exception e) {
            log.error("评估异常: {}", e.getMessage(), e);
        }
    }

    @Test
    @Order(3)
    public void testAssessService() {
        String inputPath = "src/main/resources/data/result/LightRAG_QA.jsonl";
        String detailOutputPath = "src/main/resources/data/result/modelEvaluate/LightRAG_LLMAssess_detail-1.jsonl";
        String resultOutputPath = "src/main/resources/data/result/modelEvaluate/LightRAG_LLMAssess_result-1.jsonl";

        int corePoolSize = 5;
        int maximumPoolSize = 10;
        long keepAliveTime = 60L;
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(200);
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.SECONDS,
                workQueue, threadFactory, handler);

        try {
            List<EvalRecord> records = TextUtils.readJsonlToList(inputPath, EvalRecord.class);
            log.info("待评估{}个问题", records.size());

            List<AssessService.AssessResult> assessResults = new CopyOnWriteArrayList<>();
            AtomicInteger progress = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(records.size());

            for (EvalRecord record : records) {
                executor.execute(() -> {
                    try {
                        AssessService.AssessResult result =
                                assessService.Assess(record.getQuestion(),
                                        record.getPredAnswer(), record.getGoldAnswer());
                        assessResults.add(result);
                        log.info("处理进度：{}/{}", progress.incrementAndGet(), records.size());
                    } catch (Exception e) {
                        log.error("单条评估失败", e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            Map<String, Double> llmScore = EvaluationUtils.llmScore(assessResults);
            TextUtils.writeJsonl(detailOutputPath, assessResults);
            TextUtils.writeJsonl(resultOutputPath, List.of(llmScore));
            log.info("===== 全部评估完成，结果已写入文件 =====");

        } catch (Exception e) {
            log.error("数据集测试失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 使用 LLM 模型批量评估四种 RAG 方法，输出详细文件 + 模型评分对比表格
     */
    @Test
    @Order(5)
    public void runAllLLMAssess() {
//        String inputDir="src/main/resources/data/result/evaluation/";
//        String resultDir = "src/main/resources/data/result/modelEvaluate/";
//        String[] methods = { "NaiveRAG", "HKGRAG-v2-1"};
        String inputDir="src/main/resources/data/datasets/UltraDomain/mix/";
        String resultDir = "src/main/resources/data/datasets/UltraDomain/mix/";
        String[] methods = {"HKGRAG-mix"};
//        String[] methods = {"NaiveRAG", "GraphRAG","LightRAG" ,"LeanRAG","HKGRAG"};
        int corePoolSize = 8;
        int maximumPoolSize = 20;
        long keepAliveTime = 80L;
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(200);
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.SECONDS,
                workQueue, threadFactory, handler);

        // 方法名 -> LLM评分 (contentComprehensiveness, empowerment, diversity, overall)
        Map<String, Map<String, Double>> allLLMScores = new LinkedHashMap<>();

        try {
            for (String method : methods) {
                String qaPath = inputDir + method + "_QA.jsonl";
                String detailPath = resultDir + method + "_LLMAssess_detail-5.jsonl";
                String resultPath = resultDir + method + "_LLMAssess_result-5.jsonl";

                log.info("========== 开始 LLM 评估方法: {} ==========", method);

                List<EvalRecord> records = TextUtils.readJsonlToList(qaPath, EvalRecord.class);
                log.info("[{}] 待评估 {} 条记录", method, records.size());

                List<AssessService.AssessResult> assessResults = new CopyOnWriteArrayList<>();
                AtomicInteger progress = new AtomicInteger(0);
                CountDownLatch latch = new CountDownLatch(records.size());

                for (EvalRecord record : records) {
                    executor.execute(() -> {
                        try {
                            AssessService.AssessResult result =
                                    assessService.Assess(record.getQuestion(),
                                            record.getPredAnswer(), record.getGoldAnswer());
                            assessResults.add(result);
                            log.info("[{}] 处理进度：{}/{}", method, progress.incrementAndGet(), records.size());
                        } catch (Exception e) {
                            log.error("[{}] 单条评估失败", method, e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await();

                Map<String, Double> llmScore = EvaluationUtils.llmScore(assessResults);
                TextUtils.writeJsonl(detailPath, assessResults);
                TextUtils.writeJsonl(resultPath, List.of(llmScore));

                allLLMScores.put(method, llmScore);
                log.info("[{}] LLM 评估完成: comprehensiveness={:.2f}, empowerment={:.2f}, diversity={:.2f}, overall={:.2f}",
                        method,
                        llmScore.get("comprehensiveness"),
                        llmScore.get("empowerment"),
                        llmScore.get("diversity"),
                        llmScore.get("overall"));
            }

            executor.shutdown();

            // 生成 LLM 评分对比表格
//            writeLLMComparisonTable(allLLMScores, resultDir + "LLM_Comparison-1.txt");
            log.info("========== 全部 LLM 评估完成，对比表格已生成 ==========");

        } catch (Exception e) {
            log.error("LLM 批量评估失败: {}", e.getMessage(), e);
            executor.shutdownNow();
        }
    }

    /**
     * 将各方法的 LLM 评分写入对比表格
     */
    private void writeLLMComparisonTable(Map<String, Map<String, Double>> allScores, String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("==================== 四种 RAG 方法 LLM 评估结果对比 ====================\n\n");

            String header = String.format("%-15s %12s %22s %10s %10s",
                    "Method", "Comprehensiveness", "Empowerment", "Diversity", "Overall");
            writer.write(header);
            writer.newLine();
            writer.write("-----------------------------------------------------------------------------------\n");

            for (Map.Entry<String, Map<String, Double>> entry : allScores.entrySet()) {
                Map<String, Double> s = entry.getValue();
                writer.write(String.format("%-15s %12.2f %22.2f %10.2f %10.2f",
                        entry.getKey(),
                        s.get("contentComprehensiveness"),
                        s.get("empowerment"),
                        s.get("diversity"),
                        s.get("overall")));
                writer.newLine();
            }

            writer.newLine();
            writer.write("==================== 文件清单 ====================\n");
            for (String method : allScores.keySet()) {
                writer.write("  " + method + "_LLMAssess_detail.jsonl  -> 逐条 LLM 评估详情\n");
                writer.write("  " + method + "_LLMAssess_result.jsonl  -> LLM 评分汇总\n");
            }
            writer.write("  All_Methods_LLM_Comparison-v1.txt -> 本文件（四种方法 LLM 评分对比）\n");
        }
    }

    /**
     * 从已有的 _QA.jsonl 文件重新计算指标（使用词级分词），生成新的对比表格
     */
    @Test
    @Order(6)
    public void revaluateFromQA() {
//        String resultDir = "src/main/resources/data/result/evaluation/";
//        String[] methods = {"BM25RAG", "NaiveRAG", "HybridRAG", "HKGRAG-v2-1","LeanRAG"};
        String resultDir = "src/main/resources/data/datasets/UltraDomain/agriculture/";
        String[] methods = {"NaiveRAG", "GraphRAG", "LightRAG", "HKGRAG-2"};
        Map<String, Map<String, Double>> allScores = new LinkedHashMap<>();

        try {
            for (String method : methods) {
                String qaPath = resultDir + method + "_QA.jsonl";
                log.info("========== 重新评估方法: {} ==========", method);

                List<EvalRecord> records = TextUtils.readJsonlToList(qaPath, EvalRecord.class);
                log.info("[{}] 读取 {} 条记录", method, records.size());

                // 逐条计算指标并汇总
                double totalEM = 0, totalF1 = 0, totalRougeL = 0, totalRecall = 0;
                for (EvalRecord r : records) {
                    String pred = r.getPredAnswer();
                    String gold = r.getGoldAnswer();

                    totalEM += EvaluationUtils.exactMatch(pred, gold) ? 1.0 : 0.0;
                    totalF1 += EvaluationUtils.f1Score(pred, gold);
                    totalRougeL += EvaluationUtils.rougeL(pred, gold);
                    totalRecall += EvaluationUtils.recallScore(pred, gold);
                }

                int n = records.size();
                Map<String, Double> scores = new LinkedHashMap<>();
                scores.put("EM", totalEM / n);
                scores.put("F1", totalF1 / n);
                scores.put("RougeL", totalRougeL / n);
                scores.put("Recall", totalRecall / n);
                allScores.put(method, scores);

                log.info("[{}] 重新评估完成: EM={:.4f}, F1={:.4f}, RougeL={:.4f}, Recall={:.4f}",
                        method, scores.get("EM"), scores.get("F1"), scores.get("RougeL"), scores.get("Recall"));
            }

            // 写入新的对比表格
            EvaluationUtils.writeComparisonTable(allScores, resultDir + "agriculture_All_Methods_Comparison.txt");
            log.info("========== 全部重新评估完成，新表格已生成: agriculture_All_Methods_Comparison.txt ==========");

        } catch (Exception e) {
            log.error("重新评估失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 一键运行四种 RAG 方法，自动评估，输出详细文件 + 汇总对比表格
     */
    @Test
    @Order(4)
    public void runAllMethods() {
        String inputPath = "src/main/resources/data/datasets/Bistu/qa.jsonl";
        String resultDir = "src/main/resources/data/datasets/Bistu";

        Map<String, Function<String, String>> methods = new LinkedHashMap<>();
//        methods.put("BM25RAG", q -> baseLineService.bm25Rag(q).getAnswer());
        methods.put("NaiveRAG", q -> baseLineService.naiveRAG(q).getAnswer());
//        methods.put("HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
        methods.put("HKGRAG", multiAgent::testMultiAgent);

        try {
            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);
            log.info("测试集共 {} 条数据，将用 {} 种方法分别测试", testSetList.size(), methods.size());

            // 方法名 -> 整体指标（直接复用 Evaluator 返回的 EvalSummary）
            Map<String, Map<String, Double>> allMethodScores = new LinkedHashMap<>();

            for (Map.Entry<String, Function<String, String>> entry : methods.entrySet()) {
                String methodName = entry.getKey();
                Function<String, String> ragMethod = entry.getValue();

                log.info("========== 开始测试方法: {} ==========", methodName);

                // Step1: 生成答案
                List<EvalRecord> records = new ArrayList<>();
                for (int i = 0; i < testSetList.size(); i++) {
                    TestSet t = testSetList.get(i);
                    EvalRecord record = new EvalRecord();
//                    record.setFileId(t.getFileId());
//                    record.setType(t.getType());
                    record.setQuestion(t.getQuestion());
                    record.setPredAnswer(ragMethod.apply(t.getQuestion()));
                    record.setGoldAnswer(t.getAnswer());
                    records.add(record);
                    log.info("[{}] 处理进度：{}/{}", methodName, i + 1, testSetList.size());
                }

                // Step2: 写 Q&A 文件
                String qaPath = resultDir + methodName + "_QA.jsonl";
                TextUtils.writeJsonl(qaPath, records);
                log.info("[{}] Q&A 文件已写入: {}", methodName, qaPath);

                // Step3: 评估（Evaluator 内部一次遍历完成评估+汇总，返回 summary 复用）
                String detailPath = resultDir + methodName + "_Detail.jsonl";
                String summaryPath = resultDir + methodName + "_Summary.txt";
                EvalSummary summary = Evaluator.evaluate(records, detailPath, summaryPath);
                if (summary == null) {
                    log.error("[{}] 评估失败", methodName);
                    continue;
                }

                // 直接复用 summary.overall()，不再重复遍历计算
                allMethodScores.put(methodName, summary.overall());
                log.info("[{}] 测试+评估完成", methodName);
            }

            // Step4: 生成汇总对比表格
            EvaluationUtils.writeComparisonTable(allMethodScores, resultDir + "All_Methods_Comparison.txt");
            log.info("========== 全部方法测试完成，汇总表格已生成 ==========");

        } catch (Exception e) {
            log.error("批量测试失败: {}", e.getMessage(), e);
        }
    }

}
