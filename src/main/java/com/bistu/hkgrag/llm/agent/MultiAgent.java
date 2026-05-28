//package com.bistu.hkgrag.llm.agent;
//
//import com.bistu.hkgrag.dao.milvus.model.DoCluster;
//import com.bistu.hkgrag.dao.milvus.model.DoEntity;
//import com.bistu.hkgrag.dao.milvus.model.DoFile;
//import com.bistu.hkgrag.dao.milvus.service.ClusterService;
//import com.bistu.hkgrag.dao.milvus.service.EntityService;
//import com.bistu.hkgrag.dao.milvus.service.FileService;
//import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
//import com.bistu.hkgrag.llm.model.agent.RagResult;
//import com.bistu.hkgrag.llm.model.agent.StepInfo;
//import com.bistu.hkgrag.llm.model.rerank.RerankResponse;
//import com.bistu.hkgrag.llm.service.RerankService;
//import org.checkerframework.checker.units.qual.Acceleration;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//
//@Service
//public class MultiAgent {
//    private final Logger log = LoggerFactory.getLogger(MultiAgent.class);
//    private final QuestionAnalysisAgent questionAnalysisAgent;
//    private final IntentAgent intentAgent;
//    private final EvaluationAgent evaluationAgent;
//
//
//    private final Neo4jService neo4jService;
//    private final EntityService entityService;
//    private final FileService fileService;
//    private final ClusterService clusterService;
//    private final RerankService rerankService;
//    //最大智能体轮数
//    private final int MaxRetry = 5;
//    //重排序模型最大重排个数
//    private final int RerankMaxCount = 10;
//    @Value("${HKG-RAG.retrieval.TopK}")
//    int topK;
//
//    public MultiAgent(@Autowired QuestionAnalysisAgent questionAnalysisAgent,
//                      @Acceleration IntentAgent intentAgent,
//                      @Autowired EvaluationAgent evaluationAgent,
//                      @Autowired EntityService entityService,
//                      @Autowired RerankService rerankService,
//                      Neo4jService neo4jService,
//                      @Autowired FileService fileService,
//                      @Autowired ClusterService clusterService) {
//        this.questionAnalysisAgent = questionAnalysisAgent;
//        this.intentAgent = intentAgent;
//        this.evaluationAgent = evaluationAgent;
//        this.entityService = entityService;
//        this.rerankService = rerankService;
//        this.neo4jService = neo4jService;
//        this.fileService = fileService;
//        this.clusterService = clusterService;
//    }
//
//    public RagResult MultiAgentWorkflow(String question) {
//        if (question.isEmpty() || question.isBlank()) {
//            log.error("传入多智能体流程的问题为空");
//            return new RagResult();
//
//        }
//        int retry = 1;
//        String finalAnswer = "";
//        EvaluationAgent.EvaluationResult evaluationResult;
//        String suggestion = "";
//        List<StepInfo> stepInfoList = new ArrayList<>();
//        List<String> subQuestions = new ArrayList<>();
//
//        try {
//            RagResult multiAgentResult = new RagResult();
//            while (retry < MaxRetry) {
//                String initAnswer = "";
//                if (retry == 1) {
//                    //如果是第一轮直接根据问题在向量库中对文档内容进行匹配并生成回答
//                    List<DoFile> fileList = fileService.vectorSearchByContent(question, topK);
//                    StringBuilder sb = new StringBuilder();
//                    for (int i = 0; i < fileList.size(); i++) {
//                        DoFile file = fileList.get(i);
//                        sb.append("相关文档").append(i + 1).append("：\n")
//                                .append("文档名：").append(file.getFileName()).append("\n")
//                                .append("文档内容：").append(file.getContent()).append("\n\n");
//                    }
//                    initAnswer = questionAnalysisAgent.initAnswerGeneration(question, sb.toString());
//                    StepInfo stepInfo = new StepInfo();
//                    stepInfo.setSubQuestion(question);
//                    stepInfo.setSubAnswer(initAnswer);
//                    stepInfo.setSubAccording(sb.toString());
//                    stepInfoList.add(stepInfo);
//                    multiAgentResult.setFiles(fileList);
//                }
//
//                if (retry == 2) {
//                    //将用户问题分解为子问题(问题分析智能体)
//                    QuestionAnalysisAgent.QuestionAnalysisResult questionAnalysisResult = questionAnalysisAgent.userQuestionAnalyse(question, suggestion);
//                    //放入子问题
//                    if (!questionAnalysisResult.subQuestions().isEmpty()) {
//                        subQuestions.addAll(questionAnalysisResult.subQuestions());
//                        for (String subQuestion : subQuestions) {
//                            //模型根据子问题进行意图解析 解析出 子问题涉及的关键实体列表、问题涉及的维度约束列表
//                            IntentAgent.IntentParsingResult intentParsingResult = intentAgent.intentParsing(subQuestion);
//                            List<String> subQuestion_keywords = intentParsingResult.keyEntities();
//                            Map<String, String> constraints = intentParsingResult.constraints();
//                            List<String> sub_steps = intentParsingResult.steps();
//
//                            // 1.在向量数据库中对子问题得关键词进行检索获取相关文档
//                            List<DoEntity> vector_retrieval = entityService.hybridSearchEntityList(subQuestion_keywords, topK);
//                            if (vector_retrieval.isEmpty()) {
//                                log.info("对当前子问题的关键词[{}]进行向量检索为空", subQuestion_keywords.toString());
//                            }
//                            //2.在知识图谱中根据关键词进行检索
//                            List<Map<String, Object>> nodeInfo = new ArrayList<>();
//                            List<String> entityIds = new ArrayList<>();
//                            for (String keyword : subQuestion_keywords) {
//                                nodeInfo.addAll(neo4jService.findAllNodeByName(keyword));
//                                List<String> ids = nodeInfo.stream()
//                                        .map(node -> node.get("id") != null ? node.get("id").toString() : null)
//                                        .filter(Objects::nonNull)
//                                        .toList();
//
//                                entityIds.addAll(ids);
//                            }
//                            if (entityIds.isEmpty()) {
//                                log.info("对当前子问题的关键词[{}]在知识图谱中检索节点为空", subQuestion_keywords.toString());
//                            }
//                            // 提取所有实体的 id 列表（核心：一行流提取）
//                            List<String> vectorIds = vector_retrieval.stream()
//                                    .map(DoEntity::getEntityId)
//                                    .filter(Objects::nonNull)
//                                    .toList();
//                            //合并向量和图谱检索到的节点
//                            entityIds.addAll(vectorIds);
//                            //根据实体id列表找与之关联的文档节点及属性
//                            List<Map<String, String>> relatedFiles = neo4jService.findFileByEntitiesId(entityIds);
//                            //提取 所有文档的 id 列表
//                            List<String> documentIds = relatedFiles.stream()
//                                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
//                                    .filter(Objects::nonNull)         // 过滤空值
//                                    .distinct()
//                                    .toList();
//                            //TODO 考虑是否要给子问题检索到的文档进行重排序
//
//                            //TODO 考虑是否对两条路线的文档重新进行重排序或者采用打分机制
//                            /**
//                             * 路线2 通过主题聚类获取的文档
//                             */
//                            List<DoFile> all_rerank_fileList = new ArrayList<>();
//                            //1.分析原问题的相关主题
//                            QuestionAnalysisAgent.QuestionPreRecognitionResult questionPreRecognitionResult =
//                                    questionAnalysisAgent.userQuestionPreRecognition(question);
//                            List<String> relatedTopics = questionPreRecognitionResult.relatedTopics();
//                            //2.在Cluster向量表中进行匹配
//                            List<DoCluster> clusterList = clusterService.batchHybridSearchByNameAndDescription(relatedTopics, topK);
//                            //3.获取id在图谱中找到文件id
//                            List<String> clusterIdList = clusterList.stream().map(DoCluster::getClusterId).distinct().toList();
//                            List<Map<String, String>> topicRelatedFiles = neo4jService.findFileByEntitiesId(clusterIdList);
//                            //提取 所有文档的 id 列表
//                            List<String> topicRelatedDocIds = topicRelatedFiles.stream()
//                                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
//                                    .filter(Objects::nonNull)         // 过滤空值
//                                    .distinct()
//                                    .toList();
//                            //3.根据文件id在File向量表中找
//                            List<DoFile> topicRelatedFileList = fileService.getFilesById(topicRelatedDocIds);
//                            List<DoFile> rerank_topicFileList = new ArrayList<>();
//                            for (int i = 0; i < topicRelatedFileList.size(); i += RerankMaxCount) {
//                                //批量送入rerank模型
//                                int end = Math.min(i + RerankMaxCount, topicRelatedFileList.size());
//                                List<DoFile> batch = topicRelatedFileList.subList(i, end);
//                                //批量重排
//                                RerankResponse.RerankType rerankType = RerankResponse.RerankType.LOCAL;
//                                List<DoFile> rerank_batch = rerankService.rerank(subQuestion, batch, rerankType, RerankMaxCount);
//                                rerank_topicFileList.addAll(rerank_batch);
//                            }
//                            List<DoFile> sortedTopFiles = rerank_topicFileList.stream()
//                                    .sorted(Comparator.comparing(DoFile::getContentScore).reversed())
//                                    .limit(topK)
//                                    .toList();
//
//                            all_rerank_fileList.addAll(sortedTopFiles);
//
//                            /**
//                             * 路线1 用户原问题与文档内容向量匹配
//                             */
//                            List<DoFile> fileList = fileService.vectorSearchByContent(question, topK);
//                            all_rerank_fileList.addAll(fileList);
//
//                            /**
//                             * 路线3 通过原问题的关键词匹配获取到的文档
//                             */
//                            //找到的是文件id的全文包含多个分块
////                            List<DoFile> milvus_fileList = fileService.getFilesById(documentIds);
////                            for (int i = 0; i < milvus_fileList.size(); i += RerankMaxCount) {
////                                //批量送入rerank模型
////                                int end = Math.min(i + RerankMaxCount, milvus_fileList.size());
////                                List<DoFile> batch = milvus_fileList.subList(i, end);
////                                //批量重排
////                                RerankResponse.RerankType rerankType = RerankResponse.RerankType.LOCAL;
////                                List<DoFile> rerank_batch = rerankService.rerank(subQuestion, batch, rerankType, RerankMaxCount);
////                                rerank_topicFileList.addAll(rerank_batch);
////                            }
////                            sortedTopFiles = rerank_topicFileList.stream()
////                                    .sorted(Comparator.comparing(DoFile::getContentScore).reversed())
////                                    .limit(topK)
////                                    .toList();
////                            all_rerank_fileList.addAll(sortedTopFiles);
//
//
//                            Set<String> existedChunkIds = new HashSet<>();
//                            all_rerank_fileList.removeIf(file -> !existedChunkIds.add(file.getChunkId()));
//
//                            List<DoFile> reranked_fileList = new ArrayList<>();
//
//                            for (int i = 0; i < all_rerank_fileList.size(); i += RerankMaxCount) {
//                                //批量送入rerank模型
//                                int end = Math.min(i + RerankMaxCount, all_rerank_fileList.size());
//                                List<DoFile> batch = all_rerank_fileList.subList(i, end);
//                                //批量重排
//                                RerankResponse.RerankType rerankType = RerankResponse.RerankType.LOCAL;
//                                List<DoFile> rerank_batch = rerankService.rerank(subQuestion, batch, rerankType, RerankMaxCount);
//                                reranked_fileList.addAll(rerank_batch);
//                            }
//                            reranked_fileList = reranked_fileList.stream()
//                                    .sorted(Comparator.comparing(DoFile::getContentScore).reversed())
//                                    .limit(topK)
//                                    .toList();
//
//                            multiAgentResult.setFiles(reranked_fileList);
//
//                            StringBuilder retrievalInfoSb = new StringBuilder();
//                            for (int i = 0; i < reranked_fileList.size(); i++) {
//                                DoFile doFile = reranked_fileList.get(i);
//                                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
//                                        .append("文档名：").append(doFile.getFileName()).append("\n")
//                                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
//                            }
//                            String subQuestion_retrievalInfo = retrievalInfoSb.toString();
//                            String subAnswer = intentAgent.solveSubQuestion(subQuestion, subQuestion_retrievalInfo);
//                            StepInfo stepInfo = new StepInfo();
//                            stepInfo.setSubQuestion(subQuestion);
//                            stepInfo.setSubAnswer(subAnswer);
//                            stepInfo.setSubAccording(subQuestion_retrievalInfo);
//                            stepInfoList.add(stepInfo);
//                        }
//
//                        /**
//                         * 3.由问题分析智能体生成初始答案
//                         */
//                        initAnswer = questionAnalysisAgent.initAnswerGeneration(stepInfoList);
//                        if(initAnswer.equals("")||initAnswer.equals("null")) {
//                            log.error("问题分析智能体生成初始答案失败");
//                            initAnswer = "已检索到信息，但无法生成有效答案";
//                        }
//                    }
//
//                }
//                /**
//                 * 4.将原问题与初始答案给评估智能体进行评估
//                 */
//                //TODO 子问题依据太长
//                evaluationResult = evaluationAgent.evaluate(question, initAnswer,stepInfoList.get(stepInfoList.size()-1).getSubAccording());
//                finalAnswer = initAnswer;
//                //模型认为通过
//                if (evaluationResult.success().equals(EvaluationAgent.EvaluationResult.Evaluation.PASS)) {
//                    log.info("答案评估智能体评估结果：[PASS]");
//                    log.info("第[{}]轮后智能体已获取答案", retry);
//                    break;
//                }
//                if (evaluationResult.success().equals(EvaluationAgent.EvaluationResult.Evaluation.FAIL)) {
//                    log.info("答案评估智能体评估结果：[FAIL]");
//                    log.info("第[{}]轮智能体思考已经结束，但未获取答案", retry);
//                    retry++;
//                    suggestion = evaluationResult.suggestion();
//                    if (stepInfoList != null && !stepInfoList.isEmpty()) {
//                        stepInfoList.get(stepInfoList.size() - 1).setSubSuggestion(suggestion);
//                    }
//                }
//            }
//            multiAgentResult.setAnswer(finalAnswer);
//            log.info("最终答案已经生成", finalAnswer);
//            return multiAgentResult;
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("多智能体流程错误", e);
//            return null;
//        }
//    }
//}
