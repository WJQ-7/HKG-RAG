package com.bistu.hkgrag.Service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.llm.model.rerank.RerankResponse;
import com.bistu.hkgrag.llm.service.RerankService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJson;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class RerankServiceTest {

    private RerankService rerankService;


    public RerankServiceTest(@Autowired RerankService rerankService) {
        this.rerankService = rerankService;
    }

    @Test
    public void testRerank() {
        String query = "什么是文本排序模型";
        String inputPath="src/main/resources/data/test.json";
        List<DoFile> fileList = readJson(inputPath).subList(0, 4);

        // 调用百炼
        //String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        String url = "http://10.146.130.140:8001/v1/rerank";
        String api_key = "sk-1435466c2c4049ee9c7eb8bac30c873a";
        String rerankModel = "/home/wjq/qwen3-4b-reranker";
       // String rerankModel = "qwen3-vl-rerank";

        int topK = 2;
        RerankResponse.RerankType rerankType = RerankResponse.RerankType.LOCAL;
        List<DoFile> reranked = rerankService.rerank(query, fileList, rerankType,3);
        Assertions.assertEquals(reranked.size(), topK);
    }
}
