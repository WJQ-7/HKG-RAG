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
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Nullable;
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
            @Schema(description = "检索的全部内容")
            @JsonProperty(required = true)
            String content

    ) {
    }

    @Tool(description = """
            适用场景：
            当问题查询的是人名、地名、机构名及其属性时调用。
            知识图谱中的实体主要包括：
            - 人名（教师、学生、校友等）
            - 地名（校区、楼宇、地点等）
            - 机构名（学院、部门、办公室、实验室等）
            
            工具功能：
            根据实体名称查询知识图谱中的实体节点及其属性信息。
            
            可获取的信息包括但不限于：
            - 人物属性（性别、国籍、身份、职务等）
            - 机构属性（职责、联系方式、办公地点等）
            - 地点属性（所属校区、位置说明等）
            
            调用时机：
            - 查询某个人的信息
            - 查询某个部门或机构的信息
            - 查询某个地点的信息
            - 查询实体的属性、联系方式、归属关系等
            
            参数说明：
            names：
            填写问题分析智能体提取出的相关实体名称列表。
            
            返回结果：
            返回实体节点及其属性信息。
            """)
    public RetrievalResult searchEntityByName(@ToolParam(description = "问题分析智能体返回结果中的相关实体") List<String> names) {
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
            String content = resultList.toString();
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

//    @Tool(description = """
//        适用场景：
//        当问题查询的是人名、地名、机构名的相关资料、关联文件、属性说明、归属关系时调用。
//
//        该工具会：
//        1. 根据实体名称检索相关实体
//        2. 查找实体关联文档
//        3. 返回相关文件原文内容
//
//        适用于：
//        - 查询人物相关资料
//        - 查询项目相关文件
//        - 查询机构相关通知
//        - 查询某实体涉及的文档内容
//        """)
//    public RetrievalResult searchFileByEntity(@ToolParam(description = "问题分析智能体输出的关键词列表") List<String> keywords,
//                                              @ToolParam(description = """
//                                                      问题分析智能体的规划结果。
//                                                      请直接使用问题分析结果中的：
//                                                      - 用户问题/子问题
//                                                      正确示例：
//                                                      - 校史工作办公室的联系电话是多少
//                                                      错误示例：
//                                                      - 联系电话
//                                                      - 资金总额
//                                                      - 邮箱
//                                                      不要只传属性名称。
//                                                      """) String query) {
//        if (keywords == null || keywords.isEmpty()) {
//            log.error("关键词实体列表为空");
//            return null;
//        }
//        try{
//            log.info("智能体选择[searchFileByEntity]工具，待检索的关键词列表是[{}]",keywords);
//            //根据关键词在向量库中找相关实体
//            List<DoEntity> vector_retrieval = entityService.hybridSearchEntityList(keywords, topK);
//            if (vector_retrieval.isEmpty()) {
//                log.info("对当前关键词[{}]进行向量检索为空", keywords.toString());
//            }
//            //获取相关实体id
//            List<String> entityIds = vector_retrieval.stream()
//                    .map(DoEntity::getEntityId)
//                    .filter(Objects::nonNull)
//                    .toList();
//            //根据实体id列表找与之关联的文档节点及属性
//            List<Map<String, String>> relatedFiles = neo4jService.findFileByEntitiesId(entityIds);
//            //提取 所有文档的 id 列表
//            List<String> documentIds = relatedFiles.stream()
//                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
//                    .filter(Objects::nonNull)         // 过滤空值
//                    .distinct()
//                    .toList();
//            //在指定id范围内对文档内容和问题进行向量相似度排序 取出topK
//            List<DoFile> milvus_fileList = fileService.searchFilesByIds(query,documentIds,topK);
//            if(milvus_fileList.isEmpty()){
//                return null;
//            }
//            StringBuilder retrievalInfoSb = new StringBuilder();
//            for (int i = 0; i < milvus_fileList.size(); i++) {
//                DoFile doFile = milvus_fileList.get(i);
//                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
//                        .append("文档名：").append(doFile.getFileName()).append("\n")
//                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
//            }
//            String summary = "智能体使用[searchFileByEntity]工具，以[" + keywords + "]为查询参数，" + "检索到相关文件: " +
//                    milvus_fileList.stream()
//                            .map(DoFile::getFileName)
//                            .distinct()
//                            .reduce((a, b) -> a + ", " + b)
//                            .orElse("无");
//            String content = retrievalInfoSb.toString();
//            String evidenceId = evidenceStore.save(mapper.writeValueAsString(milvus_fileList));
//            return new RetrievalResult(
//                    evidenceId,
//                    summary,
//                    content
//            );
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("工具[searchFileByEntity]调用失败", e.getMessage());
//            return null;
//        }
//    }

    @Tool(description = """
        当问题涉及：当用户问题涉及某一类相似事件、业务场景或主题相关文档时调用此工具。。

        该工具会根据主题语义匹配知识图谱中的聚类节点，适合检索“同类文档集合”。
        
        尤其适合：多个文档虽然名称不同，但内容属于同一业务场景或同一事件类型的情况。
        
        例如：
        - 近几年招生公告
        - 项目风险相关材料
        - 某个学术会议近几年的相关通知
        - 奖学金制度文件（不同学院、不同年份）
      
        """)
    public RetrievalResult searchFileByTopic(@ToolParam(description = "问题分析智能体结果中的相关话题") List<String> relatedTopics,
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
                                                      不要只传属性名称。
                                                      """)String query) {
        if (relatedTopics == null || relatedTopics.isEmpty()) {
            log.error("相关话题列表为空");
            return null;
        }
        try{
            log.info("智能体选择[searchFileByTopic]工具，待检索的关键词列表是[{}]",relatedTopics);
            //1.根据相关主题在向量库中匹配聚类节点
            List<DoCluster> clusterList = clusterService.batchHybridSearchByNameAndDescription(relatedTopics, topK);
            //2.获取聚类节点id在图谱中找到文件id
            List<String> clusterIdList = clusterList.stream().map(DoCluster::getClusterId).distinct().toList();
            List<Map<String, String>> topicRelatedFiles = neo4jService.findFileByEntitiesId(clusterIdList);
            //提取 所有文档的 id 列表
            List<String> topicRelatedDocIds = topicRelatedFiles.stream()
                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
                    .filter(Objects::nonNull)         // 过滤空值
                    .distinct()
                    .toList();
            //3.根据文件id在File向量表中找
            //TODO 问题：这里会退化回问题匹配
            List<DoFile> topicRelatedFileList = fileService.searchFilesByIds(query,topicRelatedDocIds,topK);
            if(topicRelatedFileList.isEmpty()){
                return null;
            }
            StringBuilder retrievalInfoSb = new StringBuilder();
            for (int i = 0; i < topicRelatedFileList.size(); i++) {
                DoFile doFile = topicRelatedFileList.get(i);
                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(doFile.getFileName()).append("\n")
                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
            }
            String summary = "智能体使用[searchFileByTopic]工具，以[" + relatedTopics + "]为话题，" + "检索到相关文件: " +
                    topicRelatedFileList.stream()
                            .map(DoFile::getFileName)
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("无");
            String content = retrievalInfoSb.toString();
            String evidenceId = evidenceStore.save(mapper.writeValueAsString(topicRelatedFileList));
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

    @Tool(description = """
            该方法可以优先使用。当用户提出问题时，可使用该工具进行基于语义的向量检索。
            该工具通过对用户问题进行语义理解，直接在知识库中检索最相关的文档内容。
            该方法的参数query为用户的原始问题/子问题。
            
            适用于以下情况：
            - 优先作为基础检索手段使用（推荐）
            - 用户问题是自然语言描述
            - 问题可能无法精确匹配具体实体或主题
            - 无明确实体的问题
            - 无明确主题分类的问题
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
            String content = retrievalInfoSb.toString();
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


}
