package com.bistu.hkgrag.Evaluation;

import com.bistu.hkgrag.llm.agent.TestMultiAgent;
import com.bistu.hkgrag.llm.evaluation.AssessService;
import com.bistu.hkgrag.llm.evaluation.EvaluationUtils;
import com.bistu.hkgrag.llm.evaluation.Evaluator;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class Evaluation {
    private static final Logger log = LoggerFactory.getLogger(Evaluation.class);
    private final TestMultiAgent multiAgent;
    private final BaseLineService baseLineService;
    private final AssessService assessService;

    public Evaluation(@Autowired TestMultiAgent multiAgent,
                      @Autowired BaseLineService baseLineService,
                      @Autowired AssessService assessService) {
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
        this.assessService = assessService;
    }

    /**
     * 调用检索流程 生成问题-答案的详细文件
     */
    @Test
    @Order(1)
    public void testSet(){
        String inputPath = "src/main/resources/data/test_set.jsonl";
        String outputPath = "src/main/resources/data/result/HKGRAG_test10.jsonl";
        try{
            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);
            List<EvalRecord> records = new ArrayList<>();

            log.info("待测试{}个问题", testSetList.size());
            for (int i = 0; i < testSetList.size(); i++) {
                TestSet t = testSetList.get(i);
                String pred=multiAgent.testMultiAgent(t.getQuestion());
           //     String pred=baseLineService.baselineRAG(t.getQuestion()).getAnswer();
                EvalRecord record =
                        new EvalRecord();

                record.setFileId(t.getFileId());
                record.setType(t.getType());
                record.setQuestion(t.getQuestion());
                record.setPredAnswer(pred);
                record.setGoldAnswer(t.getAnswer());
                records.add(record);
                log.info("处理进度：{}/{}",i + 1,testSetList.size());
            }
            TextUtils.writeJsonl(outputPath, records);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("数据集测试失败:{}",e.getMessage());
        }
    }

    /**
     * 对上面产生的问题-答案文档进行F1 RougeL指标评估
     * 生成指标详细文件 和 指标汇总文件
     */
    @Test
    @Order(2)
    public void evaluation(){
        try{
            String inputPath="src/main/resources/data/result/HKGRAG_test10.jsonl";
            String detailOutputPath ="src/main/resources/data/result/HKGRAG_test10_Detail.jsonl";
            String summaryOutputPath ="src/main/resources/data/result/HKGRAG_test10_Result.txt";
            List<EvalRecord> evalRecords= TextUtils.readJsonlToList(inputPath, EvalRecord.class);
            boolean success = Evaluator.evaluate(evalRecords, detailOutputPath, summaryOutputPath);
            Assertions.assertTrue(success);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("评估异常{}",e.getMessage());
        }
    }

    @Test
    @Order(3)
    public void testAssessService() {
        String inputPath = "src/main/resources/data/result/HKGRAG_test10.jsonl";
        String detailOutputPath = "src/main/resources/data/result/evaluation/HKGRAG_LLMAssess_test10_detail.jsonl";
        String resultOutputPath = "src/main/resources/data/result/evaluation/HKGRAG_LLMAssess_test10_result.jsonl";

        // ===================== 并发配置 =====================
        // 核心线程数（根据你的机器/LLM接口限流调整，推荐 5~10）
        int corePoolSize = 5;
        // 最大线程数
        int maximumPoolSize = 10;
        // 空闲线程存活时间
        long keepAliveTime = 60L;
        // 等待队列大小
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(200);
        // 线程工厂
        ThreadFactory threadFactory = Executors.defaultThreadFactory();
        // 拒绝策略：队列满了就阻塞主线程，防止OOM
        RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                workQueue,
                threadFactory,
                handler
        );

        try {
            List<EvalRecord> records = TextUtils.readJsonlToList(inputPath, EvalRecord.class);
            log.info("待评估{}个问题", records.size());

            // 线程安全的结果集合
            List<AssessService.AssessResult> assessResults = new CopyOnWriteArrayList<>();
            // 原子计数器，线程安全打印进度
            AtomicInteger progress = new AtomicInteger(0);
            // 计数器：等待所有任务完成
            CountDownLatch latch = new CountDownLatch(records.size());

            // 提交并发任务
            for (EvalRecord record : records) {
                executor.execute(() -> {
                    try {
                        String query = record.getQuestion();
                        String pred = record.getPredAnswer();
                        String gold = record.getGoldAnswer();

                        // 执行评估
                        AssessService.AssessResult result = assessService.Assess(query, pred, gold);
                        assessResults.add(result);

                        // 进度 +1
                        int current = progress.incrementAndGet();
                        log.info("处理进度：{}/{}", current, records.size());
                    } catch (Exception e) {
                        log.error("单条评估失败", e);
                    } finally {
                        // 无论成功失败，计数器-1
                        latch.countDown();
                    }
                });
            }

            // 阻塞主线程，等待所有并发任务执行完毕
            latch.await();
            // 关闭线程池
            executor.shutdown();

            // 统一计算分数、写入文件（主线程执行，无需并发）
            Map<String, Double> llmScore = EvaluationUtils.llmScore(assessResults);
            TextUtils.writeJsonl(detailOutputPath, assessResults);
            TextUtils.writeJsonl(resultOutputPath, List.of(llmScore));

            log.info("===== 全部评估完成，结果已写入文件 =====");

        } catch (Exception e) {
            e.printStackTrace();
            log.error("数据集测试失败:{}", e.getMessage());
        }
    }

}
