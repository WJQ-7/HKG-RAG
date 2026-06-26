package com.bistu.hkgrag.Service;


import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;



@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class Neo4jServiceTest {
    private Neo4jService neo4jService;


    public Neo4jServiceTest(@Autowired Neo4jService neo4jService) {
        this.neo4jService = neo4jService;
    }


    /**
     * 根据id找节点
     */
    @Test
    public void test_findNodeById(){
        String id="1dc7a58c-d164-4ce8-a25f-f5e2917dc250";
        Map<String,Object> map= neo4jService.findNodeById(id);
        System.out.println(map);
        Assertions.assertNotNull(map);

    }

    /***
     * 根据实体name找节点
     */
    @Test
    public void test_findAllNodeByName(){
        String name="吴进";
        List<Map<String,Object>> nodeList= neo4jService.findAllNodeByName(name);
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
        List<Map<String,Object>> relationList= neo4jService.findRelationshipById(Id);
        System.out.println(relationList);
        Assertions.assertNotNull(relationList);
    }

    /**
     * 根据id找文件
     */
    @Test
    public void teste_findFileById(){
        String Id="090cba92-5a4c-4526-8787-5ff7b39b0bb9";
        List<Map<String,Object>> relationList= neo4jService.findFileById(Id);
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
