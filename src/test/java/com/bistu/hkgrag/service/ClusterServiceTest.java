package com.bistu.hkgrag.service;

import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.service.ClusterService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class ClusterServiceTest {
    private ClusterService clusterService;

    public ClusterServiceTest(@Autowired ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Test
    public void test_hybridSearchByClusterName(){
        String clusterName="网络维护与升级";
        int topK=5;
        List<DoCluster> clusterList=clusterService.hybridSearchClusterName(clusterName,topK);
        Assertions.assertEquals(topK, clusterList.size());
    }

    @Test
    public void test_hybridSearchByClusterDescription(){
        String clusterDescription="";
        int topK=5;
        List<DoCluster> clusterList=clusterService.hybridSearchClusterDescription(clusterDescription,topK);
        Assertions.assertEquals(topK, clusterList.size());
    }

    @Test
    public void test_hybridSearchByClusterNameAndClusterDescription(){
        String clusterName="期间无线网络暂停使用，";
        int topK=5;
        List<DoCluster> clusterList=clusterService.hybridSearchByNameAndDescription(clusterName,topK);
        Assertions.assertEquals(topK, clusterList.size());
    }

    @Test
    public void test_batchHybridSearchByNameAndDescription(){
        List<String> nameList= Arrays.asList("考试安排","考场变更");
        int topK=5;
        List<DoCluster> clusterList=clusterService.batchHybridSearchByNameAndDescription(nameList, topK);
        Assertions.assertEquals(topK, clusterList.size());
    }
}
