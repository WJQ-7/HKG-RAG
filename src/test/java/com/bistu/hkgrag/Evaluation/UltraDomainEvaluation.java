package com.bistu.hkgrag.Evaluation;

import com.bistu.hkgrag.llm.agent.MultiAgent;
import com.bistu.hkgrag.llm.evaluation.AssessService;
import com.bistu.hkgrag.llm.evaluation.EvaluationUtils;
import com.bistu.hkgrag.llm.evaluation.Evaluator;
import com.bistu.hkgrag.llm.evaluation.Evaluator.EvalSummary;
import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.service.BaseLineService;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * UltraDomain 数据集评估测试
 * 对 agriculture/cs/legal/mix 四个领域分别进行 RAG 评估
 * <p>
 * 评估流程（每个领域独立执行）：
 * 1. 从 {domain}_qa.jsonl 读取问答对
 * 2. 用 MultiAgent 生成预测答案（支持并发）
 * 3. 输出 {domain}_MultiAgent_QA.jsonl（问答记录）
 * 4. 计算词级指标：EM / F1 / RougeL / Recall
 * 5. LLM 评估：correctness / comprehensiveness / diversity / overall
 * 6. 生成对比汇总表格
 *
 * @author wangjingqi
 */
@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class UltraDomainEvaluation {
    private static final Logger log = LoggerFactory.getLogger(UltraDomainEvaluation.class);

    private final MultiAgent multiAgent;
    private final BaseLineService baseLineService;
    private final AssessService assessService;

    // 输出目录
    private static final String RESULT_DIR = "src/main/resources/data/result/UltraDomain/";

    // 并发配置
    private static final int CORE_POOL_SIZE = 3;
    private static final int MAX_POOL_SIZE = 6;

    public UltraDomainEvaluation(@Autowired MultiAgent multiAgent,
                                 @Autowired BaseLineService baseLineService,
                                 @Autowired AssessService assessService) {
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
        this.assessService = assessService;
    }

    // ==================== agriculture 领域 ====================

    @Test
    @Order(1)
    public void evaluateAgriculture_MultiAgent() {
        evaluateDomain("agriculture", "MultiAgent", multiAgent::testMultiAgent);
    }

    @Test
    @Order(2)
    public void evaluateAgriculture_HybridRAG() {
        evaluateDomain("agriculture", "HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
    }

    @Test
    @Order(3)
    public void evaluateAgriculture_NaiveRAG() {
        evaluateDomain("agriculture", "NaiveRAG", q -> baseLineService.naiveRAG(q).getAnswer());
    }

    @Test
    @Order(4)
    public void evaluateAgriculture_BM25RAG() {
        evaluateDomain("agriculture", "BM25RAG", q -> baseLineService.bm25Rag(q).getAnswer());
    }

    // ==================== cs 领域 ====================

    @Test
    @Order(5)
    public void evaluateCS_MultiAgent() {
        evaluateDomain("cs", "MultiAgent", multiAgent::testMultiAgent);
    }

    @Test
    @Order(6)
    public void evaluateCS_HybridRAG() {
        evaluateDomain("cs", "HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
    }

    // ==================== legal 领域 ====================

    @Test
    @Order(7)
    public void evaluateLegal_MultiAgent() {
        evaluateDomain("legal", "MultiAgent", multiAgent::testMultiAgent);
    }

    @Test
    @Order(8)
    public void evaluateLegal_HybridRAG() {
        evaluateDomain("legal", "HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
    }

    // ==================== mix 领域 ====================

    @Test
    @Order(9)
    public void evaluateMix_MultiAgent() {
        evaluateDomain("mix", "MultiAgent", multiAgent::testMultiAgent);
    }

    @Test
    @Order(10)
    public void evaluateMix_HybridRAG() {
        evaluateDomain("mix", "HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
    }

    // ==================== 一键运行 ====================

    /**
     * 一键运行 agriculture 领域所有方法 + 评估 + 对比
     */
    @Test
    @Order(11)
    public void runAllMethods_Agriculture() {
        Map<String, Function<String, String>> methods = new LinkedHashMap<>();
        methods.put("BM25RAG", q -> baseLineService.bm25Rag(q).getAnswer());
        methods.put("NaiveRAG", q -> baseLineService.naiveRAG(q).getAnswer());
        methods.put("HybridRAG", q -> baseLineService.hybridRAG(q).getAnswer());
        methods.put("MultiAgent", multiAgent::testMultiAgent);

        runAllMethodsForDomain("agriculture", methods);
    }

    /**
     * 对 agriculture 领域做 LLM 评估（从已有 QA 文件）
     */
    @Test
    @Order(12)
    public void llmAssess_Agriculture() {
        llmAssessDomain("agriculture", new String[]{"MultiAgent"});
    }

    // ==================== 核心方法 ====================

    /**
     * 对指定领域用指定方法评估
     *
     * @param domain     领域名（agriculture/cs/legal/mix）
     * @param methodName 方法名（MultiAgent/HybridRAG/NaiveRAG/BM25RAG）
     * @param ragMethod  回答生成函数
     */
    private void evaluateDomain(String domain, String methodName,
                                Function<String, String> ragMethod) {
        String qaInputPath = "src/main/resources/data/datasets/UltraDomain/" + domain + "/qa.jsonl";
        String qaOutputPath = RESULT_DIR + domain + "/" + methodName + "_QA.jsonl";
        String detailOutputPath = RESULT_DIR + domain + "/" + methodName + "_Detail.jsonl";
        String summaryOutputPath = RESULT_DIR + domain + "/" + methodName + "_Summary.txt";

        try {
            // Step1: 读取 QA 数据
            List<Map<String, Object>> qaData = readQAData(qaInputPath);
            log.info("========== [{}|{}] 开始评估，共 {} 条数据 ==========",
                    domain, methodName, qaData.size());

            // Step2: 生成预测答案
            List<EvalRecord> records = new ArrayList<>();
            for (int i = 0; i < qaData.size(); i++) {
                Map<String, Object> qa = qaData.get(i);
                String question = (String) qa.get("question");

                // 答案可能是 List<String> 或 String
                Object answerObj = qa.get("answer");
                String goldAnswer;
                if (answerObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> answerList = (List<String>) answerObj;
                    goldAnswer = String.join("; ", answerList);
                } else {
                    goldAnswer = answerObj != null ? answerObj.toString() : "";
                }

                log.info("[{}|{}] 处理进度: {}/{}", domain, methodName, i + 1, qaData.size());
                String predAnswer;
                try {
                    predAnswer = ragMethod.apply(question);
                } catch (Exception e) {
                    log.error("[{}|{}] 第 {} 条生成答案失败: {}", domain, methodName, i + 1, e.getMessage());
                    predAnswer = "[ERROR] 生成答案失败: " + e.getMessage();
                }

                EvalRecord record = new EvalRecord();
                record.setFileId((String) qa.getOrDefault("context_id", ""));
                record.setType(TestSet.Type.SINGLE_HOP); // UltraDomain 默认单跳
                record.setQuestion(question);
                record.setPredAnswer(predAnswer != null ? predAnswer : "");
                record.setGoldAnswer(goldAnswer);
                records.add(record);
            }

            // Step3: 写入 QA 文件
            TextUtils.writeJsonl(qaOutputPath, records);
            log.info("[{}|{}] QA 文件已写入: {}", domain, methodName, qaOutputPath);

            // Step4: 词级评估
            EvalSummary summary = Evaluator.evaluate(records, detailOutputPath, summaryOutputPath);
            if (summary != null) {
                log.info("[{}|{}] 词级评估完成: EM={:.4f}, F1={:.4f}, RougeL={:.4f}, Recall={:.4f}",
                        domain, methodName,
                        summary.overall().get("EM"),
                        summary.overall().get("F1"),
                        summary.overall().get("RougeL"),
                        summary.overall().get("Recall"));
            }

            log.info("========== [{}|{}] 评估完成 ==========", domain, methodName);

        } catch (Exception e) {
            log.error("[{}|{}] 评估失败: {}", domain, methodName, e.getMessage(), e);
        }
    }

    /**
     * 并发生成答案（适合 MultiAgent 这类慢方法）
     */
    @SuppressWarnings("unused")
    private List<EvalRecord> generateAnswersConcurrently(
            List<Map<String, Object>> qaData, String domain, String methodName,
            Function<String, String> ragMethod) {

        List<EvalRecord> records = new CopyOnWriteArrayList<>();
        AtomicInteger progress = new AtomicInteger(0);
        int total = qaData.size();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAX_POOL_SIZE, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());

        CountDownLatch latch = new CountDownLatch(total);

        for (int idx = 0; idx < total; idx++) {
            final int i = idx;
            final Map<String, Object> qa = qaData.get(idx);
            executor.execute(() -> {
                try {
                    String question = (String) qa.get("question");
                    Object answerObj = qa.get("answer");
                    String goldAnswer;
                    if (answerObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> answerList = (List<String>) answerObj;
                        goldAnswer = String.join("; ", answerList);
                    } else {
                        goldAnswer = answerObj != null ? answerObj.toString() : "";
                    }

                    String predAnswer;
                    try {
                        predAnswer = ragMethod.apply(question);
                    } catch (Exception e) {
                        log.error("[{}|{}] 第 {} 条生成失败: {}", domain, methodName, i + 1, e.getMessage());
                        predAnswer = "[ERROR] " + e.getMessage();
                    }

                    EvalRecord record = new EvalRecord();
                    record.setFileId((String) qa.getOrDefault("context_id", ""));
                    record.setType(TestSet.Type.SINGLE_HOP);
                    record.setQuestion(question);
                    record.setPredAnswer(predAnswer != null ? predAnswer : "");
                    record.setGoldAnswer(goldAnswer);
                    records.add(record);

                    int done = progress.incrementAndGet();
                    if (done % 10 == 0 || done == total) {
                        log.info("[{}|{}] 并发进度: {}/{}", domain, methodName, done, total);
                    }
                } catch (Exception e) {
                    log.error("[{}|{}] 处理异常", domain, methodName, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待并发任务被中断", e);
        }
        executor.shutdown();

        return records;
    }

    /**
     * 对指定领域一键运行所有方法
     */
    private void runAllMethodsForDomain(String domain,
                                        Map<String, Function<String, String>> methods) {
        String qaInputPath = "src/main/resources/data/datasets/UltraDomain/" + domain + "/qa.jsonl";
        String domainResultDir = RESULT_DIR + domain + "/";

        Map<String, Map<String, Double>> allScores = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> qaData = readQAData(qaInputPath);
            log.info("========== [{}] 一键评估开始，{} 条数据，{} 种方法 ==========",
                    domain, qaData.size(), methods.size());

            for (Map.Entry<String, Function<String, String>> entry : methods.entrySet()) {
                String methodName = entry.getKey();
                Function<String, String> ragMethod = entry.getValue();

                log.info("---------- [{}|{}] 开始 ----------", domain, methodName);

                // 生成答案
                List<EvalRecord> records = new ArrayList<>();
                for (int i = 0; i < qaData.size(); i++) {
                    Map<String, Object> qa = qaData.get(i);
                    String question = (String) qa.get("question");
                    Object answerObj = qa.get("answer");
                    String goldAnswer;
                    if (answerObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> answerList = (List<String>) answerObj;
                        goldAnswer = String.join("; ", answerList);
                    } else {
                        goldAnswer = answerObj != null ? answerObj.toString() : "";
                    }

                    String predAnswer;
                    try {
                        predAnswer = ragMethod.apply(question);
                    } catch (Exception e) {
                        predAnswer = "[ERROR] " + e.getMessage();
                    }

                    EvalRecord record = new EvalRecord();
                    record.setFileId((String) qa.getOrDefault("context_id", ""));
                    record.setType(TestSet.Type.SINGLE_HOP);
                    record.setQuestion(question);
                    record.setPredAnswer(predAnswer != null ? predAnswer : "");
                    record.setGoldAnswer(goldAnswer);
                    records.add(record);

                    log.info("[{}|{}] 进度: {}/{}", domain, methodName, i + 1, qaData.size());
                }

                // 写 QA
                String qaPath = domainResultDir + methodName + "_QA.jsonl";
                TextUtils.writeJsonl(qaPath, records);

                // 评估
                String detailPath = domainResultDir + methodName + "_Detail.jsonl";
                String summaryPath = domainResultDir + methodName + "_Summary.txt";
                EvalSummary summary = Evaluator.evaluate(records, detailPath, summaryPath);
                if (summary != null) {
                    allScores.put(methodName, summary.overall());
                }
            }

            // 生成对比表格
            if (!allScores.isEmpty()) {
                EvaluationUtils.writeComparisonTable(allScores,
                        domainResultDir + domain + "_All_Methods_Comparison.txt");
            }

            log.info("========== [{}] 一键评估完成 ==========", domain);

        } catch (Exception e) {
            log.error("[{}] 一键评估失败: {}", domain, e.getMessage(), e);
        }
    }

    /**
     * LLM 评估指定领域
     */
    private void llmAssessDomain(String domain, String[] methods) {
        String domainResultDir = RESULT_DIR + domain + "/";

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAX_POOL_SIZE, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());

        Map<String, Map<String, Double>> allLLMScores = new LinkedHashMap<>();

        try {
            for (String method : methods) {
                String qaPath = domainResultDir + method + "_QA.jsonl";
                String detailPath = domainResultDir + method + "_LLMAssess_detail.jsonl";
                String resultPath = domainResultDir + method + "_LLMAssess_result.jsonl";

                log.info("========== [{}|{}] LLM评估开始 ==========", domain, method);

                List<EvalRecord> records = TextUtils.readJsonlToList(qaPath, EvalRecord.class);
                log.info("[{}|{}] 待评估 {} 条", domain, method, records.size());

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
                            log.info("[{}|{}] LLM评估进度: {}/{}",
                                    domain, method, progress.incrementAndGet(), records.size());
                        } catch (Exception e) {
                            log.error("[{}|{}] LLM单条评估失败", domain, method, e);
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
                log.info("[{}|{}] LLM评估完成: correctness={:.2f}, comprehensiveness={:.2f}, diversity={:.2f}, overall={:.2f}",
                        domain, method,
                        llmScore.get("correctness"),
                        llmScore.get("contentComprehensiveness"),
                        llmScore.get("diversity"),
                        llmScore.get("overall"));
            }

            executor.shutdown();

            // 生成 LLM 对比表
            writeLLMComparisonTable(allLLMScores,
                    domainResultDir + domain + "_LLM_Comparison.txt");

        } catch (Exception e) {
            log.error("[{}] LLM评估失败: {}", domain, e.getMessage(), e);
            executor.shutdownNow();
        }
    }

    /**
     * 读取 QA 数据
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readQAData(String path) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new FileReader(path, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> record = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(line, Map.class);
                data.add(record);
            }
        }
        return data;
    }

    /**
     * 写 LLM 评分对比表格
     */
    private void writeLLMComparisonTable(Map<String, Map<String, Double>> allScores,
                                         String outputPath) throws IOException {
        java.io.File parent = new java.io.File(outputPath).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("==================== RAG 方法 LLM 评估结果对比 ====================\n\n");

            String header = String.format("%-15s %12s %22s %10s %10s",
                    "Method", "Correctness", "Comprehensiveness", "Diversity", "Overall");
            writer.write(header);
            writer.newLine();
            writer.write("-----------------------------------------------------------------------------------\n");

            for (Map.Entry<String, Map<String, Double>> entry : allScores.entrySet()) {
                Map<String, Double> s = entry.getValue();
                writer.write(String.format("%-15s %12.2f %22.2f %10.2f %10.2f",
                        entry.getKey(),
                        s.get("correctness"),
                        s.get("contentComprehensiveness"),
                        s.get("diversity"),
                        s.get("overall")));
                writer.newLine();
            }
        }
    }
}
