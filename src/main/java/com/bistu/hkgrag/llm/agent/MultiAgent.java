package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.bistu.hkgrag.llm.model.agent.StepInfo;
import com.bistu.hkgrag.llm.model.rerank.RerankResponse;
import com.bistu.hkgrag.llm.service.RerankService;
import org.checkerframework.checker.units.qual.Acceleration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MultiAgent {
    private final Logger log = LoggerFactory.getLogger(MultiAgent.class);
    private final QuestionAnalysisAgent questionAnalysisAgent;
    private final IntentAgent intentAgent;
    private final EvaluationAgent evaluationAgent;
    private final EntityService entityService;
    private final RerankService rerankService;
    //最大智能体轮数
    private final int MaxRetry = 5;
    private final Neo4jService neo4jService;

    private final FileService fileService;
    @Value("${HKG-RAG.retrieval.TopK}")
    int topK;

    public MultiAgent(@Autowired QuestionAnalysisAgent questionAnalysisAgent,
                      @Acceleration IntentAgent intentAgent,
                      @Autowired EvaluationAgent evaluationAgent,
                      @Autowired EntityService entityService,
                      @Autowired RerankService rerankService, Neo4jService neo4jService,
                      @Autowired FileService fileService) {
        this.questionAnalysisAgent = questionAnalysisAgent;
        this.intentAgent = intentAgent;
        this.evaluationAgent = evaluationAgent;
        this.entityService = entityService;
        this.rerankService = rerankService;
        this.neo4jService = neo4jService;
        this.fileService = fileService;
    }

    public String MultiAgentWorkflow(String question) {
        if (question.isEmpty() || question.isBlank()) {
            log.error("传入多智能体流程的问题为空");
            return "";

        }
        int retry = 1;
        String finalAnswer = "";
        EvaluationAgent.EvaluationResult evaluationResult;
        String suggestion = "";
        List<StepInfo> stepInfoList = new ArrayList<>();
        List<String> subQuestions = new ArrayList<>();

        try {
            while (retry < MaxRetry) {
                String initAnswer = "";

                if (retry == 1) {
                    //如果是第一轮直接根据问题在向量库中对文档内容进行匹配并生成回答
                    List<DoFile> fileList = fileService.vectorSearchByContent(question, topK);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < fileList.size(); i++) {
                        DoFile file = fileList.get(i);
                        sb.append("相关文档").append(i + 1).append("：\n")
                                .append("文档名：").append(file.getFileName()).append("\n")
                                .append("文档内容：").append(file.getContent()).append("\n\n");
                    }
                    initAnswer = questionAnalysisAgent.initAnswerGeneration(question, sb.toString());
                    StepInfo stepInfo = new StepInfo();
                    stepInfo.setSubQuestion(question);
                    stepInfo.setSubAnswer(initAnswer);
                    stepInfo.setSubAccording(sb.toString());
                    stepInfoList.add(stepInfo);
                }

                if (retry == 2) {
                    //将用户问题分解为子问题(问题分析智能体)
                    QuestionAnalysisAgent.QuestionAnalysisResult questionAnalysisResult = questionAnalysisAgent.userQuestionAnalyse(question, suggestion);
                    //放入子问题
                    if (!questionAnalysisResult.subQuestions().isEmpty()) {
                        subQuestions.addAll(questionAnalysisResult.subQuestions());
                        for (String subQuestion : subQuestions) {
                            //模型根据子问题进行意图解析 解析出 子问题涉及的关键实体列表、问题涉及的维度约束列表
                            IntentAgent.IntentParsingResult intentParsingResult = intentAgent.intentParsing(subQuestion);
                            List<String> subQuestion_keywords = intentParsingResult.keyEntities();
                            Map<String, String> constraints = intentParsingResult.constraints();
                            List<String> sub_steps = intentParsingResult.steps();

                            // 1.在向量数据库中对子问题得关键词进行检索获取相关文档
                            List<DoEntity> vector_retrieval = entityService.hybridSearchEntityList(subQuestion_keywords, topK);
                            //2.在知识图谱中根据关键词进行检索
                            List<Map<String, Object>> nodeInfo = new ArrayList<>();
                            List<String> entityIds = new ArrayList<>();
                            for (String keyword : subQuestion_keywords) {
                                nodeInfo.addAll(neo4jService.findAllNodeByName(keyword));
                                List<String> ids = nodeInfo.stream()
                                        .map(node -> node.get("id") != null ? node.get("id").toString() : null)
                                        .filter(Objects::nonNull)
                                        .toList();

                                entityIds.addAll(ids);
                            }
                            // 提取所有实体的 id 列表（核心：一行流提取）
                            List<String> vectorIds = vector_retrieval.stream()
                                    .map(DoEntity::getEntityId)
                                    .filter(Objects::nonNull)
                                    .toList();
                            //合并向量和图谱检索到的节点
                            entityIds.addAll(vectorIds);
                            //根据实体id列表找与之关联的文档节点及属性
                            List<Map<String, String>> relatedFiles = neo4jService.findFileByEntitiesId(entityIds);
                            //提取 所有文档的 id 列表
                            List<String> documentIds = relatedFiles.stream()
                                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
                                    .filter(Objects::nonNull)         // 过滤空值
                                    .toList();
                            //TODO 考虑是否要给子问题检索到的文档进行重排序
                            List<DoFile> milvus_fileList = fileService.getFilesById(documentIds);
                            RerankResponse.RerankType rerankType= RerankResponse.RerankType.LOCAL;
                            List<DoFile> rerank_fileList=rerankService.rerank(subQuestion,milvus_fileList,rerankType);
                            StringBuilder retrievalInfoSb = new StringBuilder();
                            for (int i = 0; i < rerank_fileList.size(); i++) {
                                DoFile doFile = rerank_fileList.get(i);
                                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
                                        .append("文档名：").append(doFile.getFileName()).append("\n")
                                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
                            }
                            String subQuestion_retrievalInfo = retrievalInfoSb.toString();
                            String subAnswer = intentAgent.solveSubQuestion(subQuestion, subQuestion_retrievalInfo);
                            StepInfo stepInfo = new StepInfo();
                            stepInfo.setSubQuestion(subQuestion);
                            stepInfo.setSubAnswer(subAnswer);
                            stepInfo.setSubAccording(subQuestion_retrievalInfo);
                            stepInfoList.add(stepInfo);
                        }
                        /**
                         * 3.由问题分析智能体生成初始答案
                         */
                        initAnswer = questionAnalysisAgent.initAnswerGeneration(stepInfoList);
                    }

                }
                /**
                 * 4.将原问题与初始答案给评估智能体进行评估
                 */
                evaluationResult = evaluationAgent.evaluate(question, initAnswer,stepInfoList.get(stepInfoList.size()-1).getSubAccording());
                finalAnswer = initAnswer;
                //模型认为通过
                if (evaluationResult.success().equals(EvaluationAgent.EvaluationResult.Evaluation.PASS)) {
                    log.info("答案评估智能体评估结果：[PASS]");
                    log.info("第[{}]轮后智能体已获取答案", retry);
                    break;
                }
                if (evaluationResult.success().equals(EvaluationAgent.EvaluationResult.Evaluation.FAIL)) {
                    log.info("答案评估智能体评估结果：[FAIL]");
                    log.info("第[{}]轮智能体思考已经结束，但未获取答案", retry);
                    retry++;
                    suggestion = evaluationResult.suggestion();
                    if (stepInfoList != null && !stepInfoList.isEmpty()) {
                        stepInfoList.get(stepInfoList.size() - 1).setSubSuggestion(suggestion);
                    }
                }
            }
            log.info("最终答案已经生成", finalAnswer);
            return finalAnswer;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("多智能体流程错误", e);
            return null;
        }
    }
}
