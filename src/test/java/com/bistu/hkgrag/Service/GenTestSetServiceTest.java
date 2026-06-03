package com.bistu.hkgrag.Service;

import com.bistu.hkgrag.llm.service.GenTestSetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class GenTestSetServiceTest {
    private GenTestSetService genTestSetService;

    public GenTestSetServiceTest(@Autowired GenTestSetService genTestSetService) {
        this.genTestSetService = genTestSetService;
    }
    @Test
    public void test_genTestSet() {
        String inputPath = "src/main/resources/data/test.json";
        String outputPath = "src/main/resources/data/qa_test_set7.jsonl";
        genTestSetService.GenTestSet(inputPath, outputPath);
    }
}
