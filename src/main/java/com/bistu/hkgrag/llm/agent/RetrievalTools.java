package com.bistu.hkgrag.llm.agent;

import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
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
    private static Logger log = LoggerFactory.getLogger(RetrievalTools.class);
    @Value("${HKG-RAG.retrieval.TopK}")
    private int topK;

    public RetrievalTools(@Autowired Neo4jService neo4jService,
                          @Autowired ClusterService clusterService,
                          @Autowired EntityService entityService,
                          @Autowired FileService fileService) {
        this.neo4jService = neo4jService;
        this.clusterService = clusterService;
        this.entityService = entityService;
        this.fileService = fileService;
    }

    @Tool(description = "根据实体名称列表从知识图谱中查询实体信息,当需要查询人物、机构、项目、专业术语及相关实体属性时调用,返回结果是实体及其相关属性")
    public String searchEntityByName(List<String> names) {
        if (names == null || names.isEmpty()) {
            log.error("实体名称列表为空");
            return null;
        }
        log.info("智能体选择调用[searchEntityByName]工具，待查询的实体列表为[{}]", names);
        try {
            List<Map<String, Object>> resultList = new ArrayList<>();
            for (String name : names) {
                if (StringUtils.hasText(name)) {
                    List<Map<String, Object>> entityInfo = neo4jService.findAllNodeByName(name);
                    if (entityInfo != null && entityInfo.size() > 0) {
                        resultList.addAll(entityInfo);
                    }
                }
            }
            if (resultList.isEmpty()) {
                return null;
            }
            return resultList.toString();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchEntityByName]调用失败", e.getMessage());
            return null;
        }
    }

    @Tool(description = """
        当问题涉及：人物、机构、项目、专业术语、实体相关文档内容时调用。

        该工具会：
        1. 根据实体名称检索相关实体
        2. 查找实体关联文档
        3. 返回相关文件原文内容

        适用于：
        - 查询人物相关资料
        - 查询项目相关文件
        - 查询机构相关通知
        - 查询某实体涉及的文档内容
        """)
    public String searchFileByEntity(List<String> keywords,String query){
        if (keywords == null || keywords.isEmpty()) {
            log.error("关键词实体列表为空");
            return null;
        }
        try{
            log.info("智能体选择[searchFileByEntity]工具，待检索的关键词列表是[{}]",keywords);
            //根据关键词在向量库中找相关实体
            List<DoEntity> vector_retrieval = entityService.hybridSearchEntityList(keywords, topK);
            if (vector_retrieval.isEmpty()) {
                log.info("对当前关键词[{}]进行向量检索为空", keywords.toString());
            }
            //获取相关实体id
            List<String> entityIds = vector_retrieval.stream()
                    .map(DoEntity::getEntityId)
                    .filter(Objects::nonNull)
                    .toList();
            //根据实体id列表找与之关联的文档节点及属性
            List<Map<String, String>> relatedFiles = neo4jService.findFileByEntitiesId(entityIds);
            //提取 所有文档的 id 列表
            List<String> documentIds = relatedFiles.stream()
                    .map(docMap -> docMap.get("id"))  // 从每个文档Map中取 id 字段
                    .filter(Objects::nonNull)         // 过滤空值
                    .distinct()
                    .toList();
            //在指定id范围内对文档内容和问题进行向量相似度排序 取出topK
            List<DoFile> milvus_fileList = fileService.searchFilesByIds(query,documentIds,topK);
            if(milvus_fileList.isEmpty()){
                return null;
            }
            StringBuilder retrievalInfoSb = new StringBuilder();
            for (int i = 0; i < milvus_fileList.size(); i++) {
                DoFile doFile = milvus_fileList.get(i);
                retrievalInfoSb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(doFile.getFileName()).append("\n")
                        .append("文档内容：").append(doFile.getContent()).append("\n\n");
            }
            return retrievalInfoSb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchFileByEntity]调用失败", e.getMessage());
            return null;
        }
    }

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
    public String searchFileByTopic(@ToolParam(description = "问题相关话题") List<String> relatedTopics,  String query){
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
            return retrievalInfoSb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchFileByTopic]调用失败", e.getMessage());
            return null;
        }
    }

    @Tool(description = """
            当用户问题无法明确匹配具体实体或无法归类到某一主题聚类时，调用此工具进行通用语义检索。
           
            该工具会直接基于问题内容进行向量相似度搜索，
            返回语义最相关的文档内容。
            
            适用于：
            - 自然语言问答
            - 模糊问题查询
            - 综合性问题
            - 无明确实体的问题
            - 无明确主题分类的问题
            
            例如：
            - 学校最近有哪些重要通知
            - 网络为什么突然无法使用
            - 最近有哪些系统维护
            - 当前有哪些项目风险
            - 学校近期的重要工作有哪些
            
            该工具属于通用语义检索，
            适合作为基础检索或兜底检索方式。
            """)
    public String searchFileByQuery(String query){
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
            return retrievalInfoSb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("工具[searchFileByQuery]失败", e.getMessage());
            return null;
        }
    }


}
