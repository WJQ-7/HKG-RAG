package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RetrievalTools {
    private Neo4jService neo4jService;
    private ClusterService clusterService;
    private EntityService entityService;
    private FileService fileService;
    private EvidenceStore evidenceStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private static Logger log = LoggerFactory.getLogger(RetrievalTools.class);
    @Value("${HKG-RAG.retrieval.TopK}")
    private int topK;

    /**
     * 返回给 LLM 的内容最大字符数。
     * 超过此长度会截断，完整内容仅保存在 EvidenceStore 中。
     */
    private static final int MAX_CONTENT_TO_LLM = 4000;

    public RetrievalTools(@Autowired Neo4jService neo4jService,
                          @Autowired ClusterService clusterService,
                          @Autowired EntityService entityService,
                          @Autowired FileService fileService,
                          @Autowired EvidenceStore evidenceStore) {
        this.neo4jService = neo4jService;
        this.clusterService = clusterService;
        this.entityService = entityService;
        this.fileService = fileService;
        this.evidenceStore = evidenceStore;
    }

    @Schema(description = "检索工具统一返回结构")
    public record RetrievalResult(
            @Schema(description = "证据Id,用于从EvidenceStore中获取完整检索内容")
            @JsonProperty(required = true)
            String evidentId,
            @Schema(description = "检索摘要")
            @JsonProperty(required = true)
            String summary,
            @Schema(description = "检索内容（已截断，完整内容通过 evidenceId 从 EvidenceStore 获取）")
            @JsonProperty(required = true)
            String content

    ) {
    }

    /**
     * 截断内容，防止返回给 LLM 的内容过长撑爆 token。
     * 完整内容已通过 evidenceId 保存在 EvidenceStore 中。
     */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_CONTENT_TO_LLM) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_TO_LLM)
                + "\n\n...[内容过长已截断，完整内容可通过 evidenceId 从 EvidenceStore 获取]";
    }

    // ==================== 1. 具体细节问题（SPECIFIC_DETAIL）检索工具 ====================

    @Tool(description = """
            【适用问题类型：SPECIFIC_DETAIL（具体细节问题）】
            当用户询问明确的事实、属性、联系方式、时间等具体信息时调用。
            
            工具功能：
            根据实体名称，通过向量匹配在知识图谱中定位实体节点，返回其属性信息。
            
            知识图谱中的实体主要包括：
            - 人名（教师、学生、校友等）
            - 地名（校区、楼宇、地点等）
            - 机构名（学院、部门、办公室、实验室等）
            
            可获取的信息包括但不限于：
            - 人物属性（性别、国籍、身份、职务等）
            - 机构属性（职责、联系方式、办公地点等）
            - 地点属性（所属校区、位置说明等）
            
            调用时机（SPECIFIC_DETAIL类型问题）：
            - 仅当关键词包含明确的实体名称（具体人名、机构名、地名）时，作为 searchFileByQuery 的补充调用
            - 一般情况下 SPECIFIC_DETAIL 问题优先使用 searchFileByQuery 直接检索文档
            
            参数说明：
            names：填写问题分析智能体提取出的关键词（实体名称列表）。
            """)
    public RetrievalResult searchEntityByName(@ToolParam(description = "问题分析智能体返回结果中的关键词（实体名称列表）") List<String> names) {
        if (names == null || names.isEmpty()) {
            log.error("实体名称列表为空");
            return null;
        }
        log.info("智能体选择调用[searchEntityByName]工具，待查询的实体列表为[{}]", names);
        try {
            List<Map<String, Object>> resultList = new ArrayList<>();
            for (String name : names) {
                if (StringUtils.hasText(name)) {
                    List<DoEntity> vector_retrieval = entityService.hybridSearchEntityList(names, topK);

                    if (vector_retrieval.isEmpty()) {
                        log.info("对当前关键词[{}]进行向量检索为空", names.toString());
                    }
                    //获取相关实体id
                    List<String> entityIds = vector_retrieval.stream()
                            .map(DoEntity::getEntityId)
                            .filter(Objects::nonNull)
                            .toList();

                    List<Map<String, Object>> entityInfo = neo4jService.findNodeByEntitiesId(entityIds);
                    if (entityInfo != null && entityInfo.size() > 0) {
                        resultList.addAll(entityInfo);
                    }
                }
            }
            if (resultList.isEmpty()) {
                log.info("没有检索到有关[{}]的实体", names);
                return null;
            }

            String summary = "智能体使用[searchEntityByName]工具，以[" + names + "]为查询参数，" + "检索到相关实体: " +
                    resultList.stream()
                            .map(item -> String.valueOf(item.get("name")))
                            .filter(StringUtils::hasText)
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("无实体");
            String content = truncate(resultList.toString());
            String evidenceId = evidenceStore.save(mapper.writeValueAsString(resultList));
            return new RetrievalResult(
                    evidenceId,
                    summary,
                    content
            );
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchEntityByName]调用失败", e.getMessage());
            return null;
        }
    }

    @Tool(description = """
            【适用问题类型：SPECIFIC_DETAIL（具体细节问题）】
            通过对用户问题/子问题进行语义理解，直接在文档向量库中检索最相关的文本内容。
              
            适用以下情况：
            - SPECIFIC_DETAIL 问题的首选检索工具，直接使用原始问题做语义匹配
            - 用户问题是自然语言描述
            - 问题可能无法精确匹配具体实体或主题
            - 无明确实体的问题
            - 无明确主题分类的问题
            - 当关键词为具体实体名时，可并行调用 searchEntityByName 补充实体属性
            同时，该工具也可以作为其他实体检索或主题检索失败时的兜底方案使用。
            
            例如：
                - 学校最近有哪些重要通知
                - 网络为什么突然无法使用
                - 最近有哪些系统维护
                - 当前有哪些项目风险
                - 学校近期的重要工作有哪些
            说明：该工具属于通用语义检索能力，既可以优先使用，也可以作为补充检索手段。
            """)
    public RetrievalResult searchFileByQuery( @ToolParam(description = """
                                                      问题分析智能体的规划结果。
                                                      请直接使用问题分析结果中的：
                                                      - 用户问题/子问题
                                                      正确示例：
                                                      - 校史工作办公室的联系电话是多少
                                                      错误示例：
                                                      - 联系电话
                                                      - 资金总额
                                                      - 邮箱
                                                      不要只传属性名称。
                                                      """) String query) {
        if (query == null || query.isEmpty()) {
            log.error("相关查询为空");
        }
        try {
            log.info("智能体选择[searchFileByQuery]工具，待检索的问题是[{}]",query);
            List<DoFile> fileList = fileService.vectorSearchByContent(query, topK);
            if (fileList.isEmpty()) {
                return null;
            }
            StringBuilder retrievalInfoSb = new StringBuilder();
            for (int i = 0; i < fileList.size(); i++) {
                DoFile doFile = fileList.get(i);
                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(doFile.getFileName()).append("\n")
                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
            }
            String summary = "智能体使用[searchFileByQuery]工具，以[" + query + "]为查询参数，" + "检索到相关文件: " +
                    fileList.stream()
                            .map(DoFile::getFileName)
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("无");
            String content = truncate(retrievalInfoSb.toString());
            String evidenceId = evidenceStore.save(mapper.writeValueAsString(fileList));
            return new RetrievalResult(
                    evidenceId,
                    summary,
                    content
            );
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchFileByQuery]失败", e.getMessage());
            return null;
        }
    }

    // ==================== 2. 全局抽象问题（GLOBAL_ABSTRACT）检索工具 ====================

    @Tool(description = """
            【适用问题类型：GLOBAL_ABSTRACT（全局抽象问题）】
            当用户问题涉及涉及某一类相似事件、业务场景或主题相关文档时调用此工具。
            该工具会根据主题语义匹配知识图谱中的聚类节点，适合检索“同类文档集合”。
            
            尤其适合：多个文档虽然名称不同，但内容属于同一业务场景或同一事件类型的情况。
            
            调用时机（GLOBAL_ABSTRACT类型问题）：
            - 近几年招生公告汇总
            - 项目风险相关材料归纳
            - 某个学术会议近几年的相关通知
            - 奖学金制度文件（不同学院、不同年份）
            - 学校近年发展情况总结
            - 各学院教学质量评估
            
            """)
    public RetrievalResult searchFileByTopic(@ToolParam(description = "问题分析智能体结果中的 topics（相关主题）") List<String> relatedTopics,
                                             @ToolParam(description = """
                                                      问题分析智能体的规划结果。
                                                      请直接使用问题分析结果中的：
                                                      - 用户问题/子问题
                                                      正确示例：
                                                      - 校史工作办公室的联系电话是多少
                                                      错误示例：
                                                      - 联系电话
                                                      - 资金总额
                                                      - 邮箱
                                                      不要只传属性名称
                                                      """)String query) {
        if (relatedTopics == null || relatedTopics.isEmpty()) {
            log.error("相关话题列表为空");
            return null;
        }
        try{
            log.info("智能体选择[searchFileByTopic]工具，待检索的主题列表是[{}]",relatedTopics);
            //1.根据相关主题在向量库中匹配聚类节点
            List<DoCluster> clusterList = clusterService.batchHybridSearchByNameAndDescription(relatedTopics, topK);
            if (clusterList.isEmpty()) {
                log.info("主题[{}]未匹配到聚类节点", relatedTopics);
                return null;
            }
            //2.获取聚类节点id，在图谱中找到关联的文档节点
            List<String> clusterIdList = clusterList.stream().map(DoCluster::getClusterId).distinct().toList();
            List<Map<String, String>> topicRelatedFiles = neo4jService.findFileByEntitiesId(clusterIdList);
            //提取所有文档的 id 列表
            List<String> topicRelatedDocIds = topicRelatedFiles.stream()
                    .map(docMap -> docMap.get("id"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            //3.根据文件id在File向量表中按query语义精排
            List<DoFile> topicRelatedFileList;
            if (topicRelatedDocIds.isEmpty()) {
                // 如果没有关联文档，直接用主题在全库检索
                topicRelatedFileList = fileService.vectorSearchByContent(query, topK);
            } else {
                topicRelatedFileList = fileService.searchFilesByIds(query, topicRelatedDocIds, topK);
            }
            if(topicRelatedFileList.isEmpty()){
                return null;
            }

            //4. 构建返回内容：包含主题聚类节点信息 + 文档内容
            StringBuilder retrievalInfoSb = new StringBuilder();
            retrievalInfoSb.append("【匹配的主题聚类节点】\n");
            for (int i = 0; i < Math.min(clusterList.size(), 5); i++) {
                DoCluster cluster = clusterList.get(i);
                retrievalInfoSb.append("- 聚类名：").append(cluster.getClusterName())
                        .append("，描述：").append(cluster.getDescription() != null ? cluster.getDescription() : "无")
                        .append("\n");
            }
            retrievalInfoSb.append("\n【相关文档内容】\n");
            for (int i = 0; i < topicRelatedFileList.size(); i++) {
                DoFile doFile = topicRelatedFileList.get(i);
                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(doFile.getFileName()).append("\n")
                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
            }
            String summary = "智能体使用[searchFileByTopic]工具，以[" + relatedTopics + "]为话题，" + "匹配到聚类节点: " +
                    clusterList.stream()
                            .map(DoCluster::getClusterName)
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("无") +
                    "，检索到相关文件: " +
                    topicRelatedFileList.stream()
                            .map(DoFile::getFileName)
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("无");
            // 保存完整信息（聚类节点 + 文档）
            Map<String, Object> combinedResult = Map.of(
                    "clusters", clusterList,
                    "files", topicRelatedFileList
            );
            String content = truncate(retrievalInfoSb.toString());
            String evidenceId = evidenceStore.save(mapper.writeValueAsString(combinedResult));
            return new RetrievalResult(
                    evidenceId,
                    summary,
                    content
            );
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchFileByTopic]调用失败", e.getMessage());
            return null;
        }
    }

    // ==================== 3. 多跳推理问题（MULTI_HOP）检索工具 ====================

    private static final int MAX_TRIPLES = 10;

    @Tool(description = """
            【适用问题类型：MULTI_HOP（多跳推理问题）】
            根据实体名称在知识图谱中定位实体节点，返回实体属性及其关联的三元组关系。
            
            工具功能：
            1. 将实体名称通过向量匹配定位到知识图谱中的实体节点
            2. 返回匹配到的实体节点属性摘要
            3. 返回该实体在知识图谱中最多10条关联三元组，格式为：(实体名) -[关系类型]-> (邻居名)
            
            三元组格式说明：
            每条三元组描述实体与邻居之间的关系，例如：
            (张三) -[属于]-> (计算机学院)
            (计算机学院) -[参与项目]-> (涉密项目A)
            
            多跳推理策略：
            - 阅读返回的三元组列表，理解实体有哪些关联关系
            - 根据问题语义，找到最相关的三元组，提取其中的邻居实体名
            - 用邻居实体名再次调用本工具，获取邻居的三元组，继续推理
            - 可重复此步骤，逐步扩展直到获取足够信息回答多跳问题
            
            调用时机：
            - 多跳推理问题定位起始实体
            - 需要查看实体有哪些关系关联
            - 拿到邻居实体名后，继续用邻居名扩展查询
            
            参数说明：
            names：填写问题分析智能体提取的关键词（实体名称列表）
            """)
    public RetrievalResult getEntityTriples(
            @ToolParam(description = "问题分析智能体提取的关键词（实体名称列表）") List<String> names) {
        if (names == null || names.isEmpty()) {
            log.error("实体名称列表为空");
            return null;
        }
        log.info("智能体选择[getEntityTriples]工具，实体列表[{}]", names);
        try {
            // Step1: 向量匹配实体名 → 获取实体ID，只保留"内容实体"
            List<DoEntity> vectorRetrieval = entityService.hybridSearchEntityList(names, topK);
            if (vectorRetrieval.isEmpty()) {
                log.info("对实体[{}]进行向量检索为空", names);
                return null;
            }
            List<String> entityIds = vectorRetrieval.stream()
                    .map(DoEntity::getEntityId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            // Step2: 检索内容实体节点（在 Neo4j 层面按标签"内容实体"过滤）
            List<Map<String, Object>> matchedEntities = neo4jService.findNodeByEntitiesIdAndLabel(entityIds, "内容实体");
            if (matchedEntities.isEmpty()) {
                log.info("实体[{}]在图谱中未找到内容实体", names);
                return null;
            }
            List<String> contentEntityIds = matchedEntities.stream()
                    .map(e -> String.valueOf(e.getOrDefault("entityId", "")))
                    .filter(id -> !id.isEmpty())
                    .distinct()
                    .toList();

            // Step3: 获取三元组（主体-关系-客体），最多10条(优先选择关系多的实体)
            List<Map<String, Object>> triples = neo4jService.findTriplesByEntityIds(contentEntityIds, MAX_TRIPLES);

            // 构建实体属性摘要（只展示内容实体）
            StringBuilder entitySummary = new StringBuilder();
            for (int i = 0; i < Math.min(matchedEntities.size(), 5); i++) {
                Map<String, Object> entity = matchedEntities.get(i);
                String name = String.valueOf(entity.getOrDefault("name", entity.getOrDefault("entityName", "未知")));
                String labels = String.valueOf(entity.getOrDefault("labels", entity.getOrDefault("entityType", "")));
                String entityId = String.valueOf(entity.getOrDefault("entityId", ""));
                entitySummary.append("实体").append(i + 1).append("：").append(name)
                        .append("，标签：").append(labels)
                        .append("，ID：").append(entityId).append("\n");
            }

            // 构建三元组描述（内容实体优先，同组内按热度降序）
            StringBuilder tripleList = new StringBuilder();
            if (triples.isEmpty()) {
                tripleList.append("该实体在知识图谱中无关联关系。\n");
            } else {
                tripleList.append("共").append(triples.size()).append("条三元组（内容实体优先，按热度降序）：\n");
                for (int i = 0; i < triples.size(); i++) {
                    Map<String, Object> t = triples.get(i);
                    String srcName = String.valueOf(t.getOrDefault("sourceName", "?"));
                    String relType = String.valueOf(t.getOrDefault("relationType", "?"));
                    String tgtName = String.valueOf(t.getOrDefault("targetName", "?"));
                    String tgtLabels = String.valueOf(t.getOrDefault("targetLabels", ""));
                    String tgtId = String.valueOf(t.getOrDefault("targetId", ""));
                    Object degree = t.getOrDefault("neighborDegree", "?");
                    tripleList.append(String.format("%d. (%s) -[%s]-> (%s) [标签=%s, ID=%s, 出度=%s]\n",
                            i + 1, srcName, relType, tgtName, tgtLabels, tgtId, degree));
                }
            }

            // 构建返回内容
            StringBuilder retrievalInfoSb = new StringBuilder();
            retrievalInfoSb.append("【匹配到的实体节点】\n");
            retrievalInfoSb.append(entitySummary);
            retrievalInfoSb.append("\n【关联三元组】\n");
            retrievalInfoSb.append(tripleList);
            retrievalInfoSb.append("\n提示：如需继续多跳推理，请提取三元组中邻居的实体名，再次调用本工具。");

            String summary = "智能体使用[getEntityTriples]工具，以[" + names + "]为查询，" +
                    "匹配到实体" + matchedEntities.size() + "个，" +
                    "返回三元组" + triples.size() + "条";
            String content = truncate(retrievalInfoSb.toString());

            Map<String, Object> combinedResult = Map.of(
                    "matchedEntities", matchedEntities,
                    "triples", triples
            );
            String evidenceId = evidenceStore.save(mapper.writeValueAsString(combinedResult));
            return new RetrievalResult(evidenceId, summary, content);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[getEntityTriples]调用失败", e.getMessage());
            return null;
        }
    }

}
