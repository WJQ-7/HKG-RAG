package com.bistu.hkgrag.Service;

import com.bistu.hkgrag.dao.neo4j.model.Triple;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJsonlToTriple;


@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class Neo4jServiceTest {
    private Neo4jService tripleService;
    @Autowired
    private Neo4jService neo4jService;

    public Neo4jServiceTest(@Autowired Neo4jService tripleService) {
        this.tripleService = tripleService;
    }

    /**
     * 文档节点和文档内容节点的插入方法一致
     * @param inputPath 待插入节点文件路径
     */
    private void processNodeFile(String inputPath) {
        try{
            List<Triple> triples=readJsonlToTriple(inputPath);

            Map<String,Map<String, Object>> mapMap = new HashMap<>();
            for(Triple triple:triples){
                String subjectId=triple.getSubjectId();
                mapMap.putIfAbsent(subjectId,new HashMap<>());
                Map<String,Object> map= mapMap.get(subjectId);
                map.put("label",triple.getSubjectType());
                map.put("id",subjectId);
                map.put("name",triple.getSubject());
                if(triple.getPredicate()!=null&& !triple.getPredicate().equals("")){
                    map.put(triple.getPredicate(),triple.getObject());
                }

            }
            for (Map<String, Object> props : mapMap.values()) {
                Boolean success=tripleService.insertNode(props);
                System.out.println(success
                        ? "插入节点成功：" + props.get("id")
                        : "插入节点失败：" + props.get("id"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("插入文件节点失败");
        }

    }

    /**
     * 在neo4j中插入节点
     */
    @Test
    @Order(1)
    public void test_createFileNode(){
//        String inputFilePath="src/main/resources/data/fileEntities.jsonl";
//        String inputEntityPath="src/main/resources/data/contentEntities.jsonl";
        String inputTopicPath="src/main/resources/data/topicEntities1.jsonl";
//        processNodeFile(inputFilePath);
//        processNodeFile(inputEntityPath);
        processNodeFile(inputTopicPath);

    }

    /**
     * 插入关系
     */
    @Test
    @Order(2)
    public void test_createRelationship(){
       // String inputFilePath="src/main/resources/data/relations.jsonl";
        String inputFilePath="src/main/resources/data/topicRelations1.jsonl";
        int count=0;
        try{
            List<Triple>  relationList=readJsonlToTriple(inputFilePath);
            for(Triple triple:relationList){
                boolean success=tripleService.createRelationship(triple);
                if(success){
                    count++;
                }else{
                    System.out.println("关系未创建: "+triple.getId());
                }
            }
            Assertions.assertEquals(relationList.size(), count);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    /**
     * 根据id找节点
     */
    @Test
    public void test_findNodeById(){
        String id="1dc7a58c-d164-4ce8-a25f-f5e2917dc250";
        Map<String,Object> map=tripleService.findNodeById(id);
        System.out.println(map);
        Assertions.assertNotNull(map);

    }

    /***
     * 根据实体name找节点
     */
    @Test
    public void test_findAllNodeByName(){
        String name="吴进";
        List<Map<String,Object>> nodeList=tripleService.findAllNodeByName(name);
        System.out.println(nodeList);
        nodeList.forEach(node -> {
            System.out.println("==================================");
            node.forEach((key, value) -> {
                System.out.println(key + " = " + value);
            });
        });
        Assertions.assertNotNull(nodeList);
    }

    /**
     * 根据id找关系
     */
    @Test
    public void test_findRelationshipById(){
        String Id="090cba92-5a4c-4526-8787-5ff7b39b0bb9";
        List<Map<String,Object>> relationList=tripleService.findRelationshipById(Id);
        System.out.println(relationList);
        Assertions.assertNotNull(relationList);
    }

    /**
     * 根据id找文件
     */
    @Test
    public void teste_findFileById(){
        String Id="090cba92-5a4c-4526-8787-5ff7b39b0bb9";
        List<Map<String,Object>> relationList=tripleService.findFileById(Id);
        System.out.println(relationList);
        Assertions.assertNotNull(relationList);
    }

    /**
     * 根据节点label找节点
     */
    @Test
    public void test_findFileByLabel(){
        String label="文档";
        List<Map<String,String>> list=neo4jService.findNodeByLabel(label);
        System.out.println(list);
    }

    @Test
    public void test_findFileByEntitiesId(){
        List<String> idList= Arrays.asList("d58feff3-b38d-45b3-b239-ccbe8a3625bd", "e06d841f-4902-4aa1-8a80-f62e1c1af1d3");
        List<Map<String, String>> nodeInfo=neo4jService.findFileByEntitiesId(idList);
        Assertions.assertNotNull(nodeInfo);

    }




}
