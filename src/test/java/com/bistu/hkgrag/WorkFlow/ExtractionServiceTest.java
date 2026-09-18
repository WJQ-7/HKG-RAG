package com.bistu.hkgrag.WorkFlow;

import com.bistu.hkgrag.llm.service.GenClusterService;
import com.bistu.hkgrag.llm.service.ExtractionService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ExtractionServiceTest {
    private ExtractionService extractionService;
    private GenClusterService clusterService;


    public ExtractionServiceTest(@Autowired ExtractionService extractionService,
                                 @Autowired GenClusterService clusterService) {
        this.extractionService = extractionService;
        this.clusterService = clusterService;
    }



    /**
     * 抽取实体、属性及关系
     */
    @Test
    @Order(1)
    public void testExtractionService() {
        String inputPath="src/main/resources/data/datasets/Bistu/chunk.json";
        String outputPath="src/main/resources/data/datasets/Bistu";
        extractionService.entitiesAndRelationExtractionMultiThread(inputPath, outputPath);
    }

    /**
     * 生成文档实体的主题
     */
    @Test
    @Order(2)
    public void testFileTopicExtraction() {
        String inputPath="src/main/resources/data/datasets/Bistu/chunk.json";
        String outputPath="src/main/resources/data/datasets/Bistu/chunk_topic.jsonl";
        extractionService.fileTopicExtraction(inputPath,outputPath);

    }

    /**
     * 对文件进行聚类提取出主题节点并在指定目录下生成文件
     */
    @Test
    @Order(3)
    public void test_topicCluster(){
        String inputPath="src/main/resources/data/datasets/Bistu/chunk_topic.jsonl";
        String outputPath="src/main/resources/data/datasets/Bistu";
        clusterService.topicClusterV2(inputPath, outputPath);
    }




}
