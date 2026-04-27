package com.bistu.hkgrag.service;

import com.bistu.hkgrag.dao.milvus.model.DoEntity;
import com.bistu.hkgrag.dao.milvus.service.EntityService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class EntityServiceTest {
    private EntityService entityService;

    public EntityServiceTest(@Autowired EntityService entityService) {
        this.entityService = entityService;
    }

    @Test
    public void test_hybridSearchEntity() {
        String entityName = "吴进";
        int topK=5;
        List<DoEntity> entityList = entityService.hybridSearchEntity(entityName, topK);
        System.out.println(entityList);
        Assertions.assertFalse(entityList.isEmpty());

    }
}
