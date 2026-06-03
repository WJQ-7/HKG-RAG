package com.bistu.hkgrag.Evaluation;

import com.bistu.hkgrag.llm.agent.TestMultiAgent;
import com.bistu.hkgrag.llm.evaluation.Evaluator;
import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.service.BaseLineService;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class Evaluation {
    private static final Logger log = LoggerFactory.getLogger(Evaluation.class);
    private final TestMultiAgent multiAgent;
    private final BaseLineService baseLineService;

    public Evaluation(@Autowired TestMultiAgent multiAgent,
                      @Autowired BaseLineService baseLineService) {
        this.multiAgent = multiAgent;
        this.baseLineService = baseLineService;
    }

    @Test
    public void testSet(){
        String inputPath = "src/main/resources/data/test_set.jsonl";
        String outputPath = "src/main/resources/data/result/testMultiAgent_test5.jsonl";
        try{
            List<TestSet> testSetList = TextUtils.readJsonlToList(inputPath, TestSet.class);
            List<EvalRecord> records = new ArrayList<>();
            log.info("待测试{}个问题", testSetList.size());
            for (int i = 0; i < records.size(); i++) {
                TestSet t = testSetList.get(i);
                String pred=multiAgent.testMultiAgent(t.getQuestion());
                EvalRecord record =
                        new EvalRecord();

                record.setFileId(t.getFileId());
                record.setType(t.getType());
                record.setQuestion(t.getQuestion());
                record.setPredAnswer(pred);
                record.setGoldAnswer(t.getAnswer());
                records.add(record);
                log.info("处理进度：{}/{}",i + 1,records.size());
            }
            TextUtils.writeJsonl(outputPath, records);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("数据集测试失败:{}",e.getMessage());
        }
    }

    @Test
    public void evaluation(){
        try{
            String inputPath="src/main/resources/data/result/leanRag_predict.jsonl";
            String detailOutputPath ="src/main/resources/data/result/LeanRag_Detail.txt";
            String summaryOutputPath ="src/main/resources/data/result/LeanRag_Result.txt";
            List<EvalRecord> evalRecords= TextUtils.readJsonlToList(inputPath, EvalRecord.class);
            boolean success = Evaluator.evaluate(evalRecords, detailOutputPath, summaryOutputPath);
            Assertions.assertTrue(success);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("评估异常{}",e.getMessage());
        }
    }

}
