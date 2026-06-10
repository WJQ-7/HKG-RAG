package com.bistu.hkgrag.llm.evaluation;

import com.bistu.hkgrag.llm.model.agent.EvalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Evaluator {
    private final static Logger log= LoggerFactory.getLogger(Evaluator.class);
    /**
     * 单条评估
     * @param record 评估记录
     * @return 带有结果的评估记录
     */
    public static EvalRecord evaluate(
            EvalRecord record
    ) {
        record.setEm(
                EvaluationUtils.exactMatch(
                        record.getPredAnswer(),
                        record.getGoldAnswer()
                )
        );

        record.setF1(
                EvaluationUtils.f1Score(
                        record.getPredAnswer(),
                        record.getGoldAnswer()
                )
        );

        record.setRougeL(
                EvaluationUtils.rougeL(
                        record.getPredAnswer(),
                        record.getGoldAnswer()
                )
        );

        record.setRecall(
                EvaluationUtils.recallScore(
                        record.getPredAnswer(),
                        record.getGoldAnswer()
                )
        );

        return record;
    }

    /**
     * 输出详细评估结果
     * @param records 详细评估记录
     * @param outputPath 输出路径
     * @throws Exception 异常
     */
    public static void writeDetailResult(
            List<EvalRecord> records,
            String outputPath
    ) throws Exception {

        ObjectMapper mapper =
                new ObjectMapper();

        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(outputPath)
                     )) {

            for (EvalRecord record : records) {

                writer.write(
                        mapper.writeValueAsString(record)
                );

                writer.newLine();
            }
        }
    }

    /**
     * 对详细记录进行汇总并写入指定路径
     * @param records 详细记录
     * @param outputPath 总体结果输出路径
     * @throws Exception
     */
    public static void writeSummaryResult(
            List<EvalRecord> records,
            String outputPath
    ) throws Exception {

        Map<String,Integer> countMap =
                new HashMap<>();

        Map<String,Double> emMap =
                new HashMap<>();

        Map<String,Double> f1Map =
                new HashMap<>();

        Map<String,Double> rougeMap =
                new HashMap<>();
        Map<String, Double> recallMap = new HashMap<>();

        double totalEM = 0;
        double totalF1 = 0;
        double totalRouge = 0;
        double totalRecall = 0;

        for(EvalRecord record : records){

            String type =
                    record.getType().toString();

            countMap.put(
                    type,
                    countMap.getOrDefault(type,0)+1
            );

            emMap.put(
                    type,
                    emMap.getOrDefault(type,0.0)
                            + (record.isEm()?1:0)
            );

            f1Map.put(
                    type,
                    f1Map.getOrDefault(type,0.0)
                            + record.getF1()
            );

            rougeMap.put(
                    type,
                    rougeMap.getOrDefault(type,0.0)
                            + record.getRougeL()
            );

            recallMap.put(
                    type,
                    recallMap.getOrDefault(type, 0.0)
                            + record.getRecall()
            );

            totalEM += record.isEm()?1:0;
            totalF1 += record.getF1();
            totalRouge += record.getRougeL();
            totalRecall += record.getRecall();
        }

        try(BufferedWriter writer =
                    new BufferedWriter(
                            new FileWriter(outputPath)
                    )){

            writer.write(
                    "=========== RESULT ===========\n\n"
            );

            for(String type : countMap.keySet()){

                int count =
                        countMap.get(type);

                writer.write(
                        String.format(
                                "Type : %s Count: %d\n",
                                type,
                                count
                        )
                );

                writer.write(
                        String.format(
                                "EM : %.4f\n",
                                emMap.get(type)/count
                        )
                );

                writer.write(
                        String.format(
                                "F1 : %.4f\n",
                                f1Map.get(type)/count
                        )
                );

                writer.write(
                        String.format(
                                "ROUGE-L : %.4f\n",
                                rougeMap.get(type)/count
                        )
                );

                writer.write(
                        String.format(
                                "RECALL : %.4f\n",
                                recallMap.get(type)/count
                        )
                );

                writer.write(
                        "---------------------------\n"
                );
            }

            writer.write(
                    "\n=========== OVERALL ===========\n"
            );

            writer.write(
                    String.format(
                            "测试集总数: %d\n",
                            records.size()
                    )
            );

            writer.write(
                    String.format(
                            "EM : %.4f\n",
                            totalEM/records.size()
                    )
            );

            writer.write(
                    String.format(
                            "F1 : %.4f\n",
                            totalF1/records.size()
                    )
            );

            writer.write(
                    String.format(
                            "ROUGE-L : %.4f\n",
                            totalRouge/records.size()
                    )
            );

            writer.write(
                    String.format(
                            "RECALL : %.4f\n",
                            totalRecall/records.size()
                    )
            );
        }
    }

    /**
     * 评估总方法
     * 负责将模型回答后的记录进行评估并写入文件
     * @param records 测试评估记录
     * @param detailOutputPath 详细评估结果输出路径
     * @param allOutputPath 总体评估结果输出路径
     * @return
     */
    public static boolean evaluate(List<EvalRecord> records,
                                   String detailOutputPath,
                                   String allOutputPath
    ) {
        log.info("待评估[{}]条数据",records.size());
        try {

            // 1. 单条评估
            for (EvalRecord record : records) {
                evaluate(record);
            }
            log.info("所有记录评估完成");
            // 2. 输出详细结果
            writeDetailResult(records, detailOutputPath);
            log.info("详细评估结果已写入: {}", detailOutputPath);
            // 3. 输出汇总结果
            writeSummaryResult(
                    records,
                    allOutputPath
            );
            log.info("汇总评估结果已写入: {}", allOutputPath);
            log.info("评估完成");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("评估失败{}", e.getMessage());
            return false;
        }
    }


}
