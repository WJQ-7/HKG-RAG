package com.bistu.hkgrag.Service;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class MilvusServiceTest {
    private EntityService entityService;
    private FileService fileService;
    private EmbeddingModel embeddingModel;

    public MilvusServiceTest(@Autowired EntityService entityService,
                             @Autowired FileService fileService,
                             @Autowired @Qualifier("qwenEmbeddingModel") EmbeddingModel embeddingModel) {
        this.entityService = entityService;
        this.fileService = fileService;
        this.embeddingModel = embeddingModel;
    }

    @Test
    @Order(1)
    public void testMilvusEntityService(){

        String inputContentEntitiesPath = "src/main/resources/data/contentEntities.jsonl";
        List<DoEntity> entityList=new ArrayList<>();
        Gson gson=new Gson();
        try{
            /**
             * 1.读取文件
             */
            BufferedReader reader= Files.newBufferedReader(Path.of(inputContentEntitiesPath));
            String line;
            Set<String> existedSubjectIds = new HashSet<>();
            /**
             * 2.映射成数据库的model
             */
            while((line=reader.readLine())!=null){
                JsonObject json=gson.fromJson(line, JsonObject.class);
                if(json.get("subjectId")!=null){
                    String subjectId=json.get("subjectId").getAsString();
                    if(existedSubjectIds.contains(subjectId)){
                        continue;
                    }
                    existedSubjectIds.add(subjectId);
                    DoEntity entity=new DoEntity();
                    entity.setEntityId(subjectId);
                    entity.setEntityName(json.get("subject").getAsString());
                    entity.setEntityNameVector(embeddingModel.embed(entity.getEntityName()));
                    entityList.add(entity);
                }
            }
            reader.close();
            System.out.println("获取到"+entityList.size()+"个内容实体");
            /**
             * 3.插入数据库
             */
            if(entityService.createEntityCollection()){
                Boolean success= entityService.insertEntities(entityList);
                if(success){
                    System.out.println("插入文件内容实体成功");
                }
                else {
                    System.out.println("插入文件内容实体失败");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    @Test
    @Order(2)
    public void testMilvusFileService(){
        String inputFileEntitiesPath = "src/main/resources/data/chunk_topic.jsonl";
        List<DoFile> fileList=new ArrayList<>();
        Gson gson=new Gson();
        try{
            /**
             * 1.读取文件
             */
            BufferedReader reader= Files.newBufferedReader(Path.of(inputFileEntitiesPath));
            String line;
            Set<String> existedSubjectIds = new HashSet<>();
            /**
             * 2.映射成数据库的model
             */
            while((line=reader.readLine())!=null){
                JsonObject json=gson.fromJson(line, JsonObject.class);
                if(json.get("chunkId")!=null){
                    String chunkId=json.get("chunkId").getAsString();
                    if(existedSubjectIds.contains(chunkId)){
                        continue;
                    }
                    existedSubjectIds.add(chunkId);
                    DoFile file=new DoFile();
                    file.setFileId(json.get("fileId").getAsString());
                    file.setChunkId(chunkId);
                    file.setFileName(json.get("fileName").getAsString());
                    file.setTopic(json.get("topic").getAsString());
                    file.setContent(json.get("content").getAsString());
                    file.setTopicVector(embeddingModel.embed(file.getTopic()));
                    file.setContentVector(embeddingModel.embed(file.getContent()));
                    fileList.add(file);
                }
            }
            reader.close();
            System.out.println("获取到"+fileList.size()+"个文档实体");
            /**
             * 3.插入数据库
             */
            if(fileService.createFileCollection()){
                Boolean success= fileService.insertFiles(fileList);
                if(success){
                    System.out.println("插入文件实体成功");
                }
                else {
                    System.out.println("插入文件实体失败");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    @Test
    public void test_FileHybridRetrieval(){
        String content="补考地点变更通知由于暑期教室改造，健翔桥校区教三楼不能正常进行听力放音，原大学英语考试地点变更如下：8月26日上午10:20-12:20的大英（2）考试地点由3-五阶变更为1-三阶。8月26日下午13:30-15:30的大英（4）和大英（2）A班考试地点由3-五阶变更为1-三阶。教务处2016年8月24日";
        List<DoFile> exactFileList=fileService.exactSearchByContent(content,5);
        Assertions.assertNotNull(exactFileList);

//        List<DoFile> bm25FileList=fileService.bm25SearchByContent(content,5);
//        Assertions.assertEquals(bm25FileList.size(),5);

        List<DoFile> vecFileList=fileService.vectorSearchByContent(content,5);
        Assertions.assertEquals(vecFileList.size(),5);

        List<DoFile> fileList=fileService.hybridSearchByContent(content,5);
        Assertions.assertEquals(fileList,5);

        String topic="面向教职工的汽车养护优惠服务及相关规定";
        List<DoFile> exactFileListByTopic=fileService.exactSearchByTopic(topic,5);
        Assertions.assertNotNull(exactFileListByTopic);
        List<DoFile> vecFileListByTopic=fileService.vectorSearchByTopic(topic,5);
        Assertions.assertEquals(vecFileListByTopic.size(),5);
        List<DoFile> fileListByTopic=fileService.hybridSearchByTopic(topic,5);
        Assertions.assertEquals(fileListByTopic.size(),5);

    }

    @Test
    public void test_EntityRetrieval(){
        String name="心理健康教育广场宣传活动";
        int topK=5;
        List<DoEntity> exactList=entityService.exactSearchByEntityName(name,topK);
        Assertions.assertNotNull(exactList);
        List<DoEntity> vectorList=entityService.vectorSearchByEntityName(name,topK);
        Assertions.assertEquals(vectorList.size(),topK);
        List<DoEntity>  entities=entityService.hybridSearchEntity(name,topK);
        Assertions.assertEquals(entities.size(),topK);
    }

    @Test
    public void test_searchFilesByIds(){
        List<String> ids = Arrays.asList(
                "090c05c6-4075-42cd-920b-25ba97f6eae2",
                "a0b8a7b0-183e-47b3-a24a-e02e91f903db",
                "3e679d63-8aaf-4111-a6cb-535e0d01a4a9",
                "451881d7-6339-4d49-878e-b528f6a4c8c4",
                "43bf7ca3-a0d9-45d3-8f63-83dff89e242a",
                "2dc8962c-119e-4d39-88e3-6e58e1539179"
        );
        String query="2016年健翔桥校区大学英语考试地点变更的原因";
        int topK=6;
        List<DoFile> fileList=fileService.searchFilesByIds(query,ids,topK);
        Assertions.assertNotNull(fileList);
    }
}
