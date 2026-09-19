package com.bistu.hkgrag.WorkFlow;


import com.bistu.hkgrag.preprocess.clean.FileChunkService;
import com.bistu.hkgrag.preprocess.parser.DocumentParserService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DocPreprocess {
    private DocumentParserService documentParserService;
    private FileChunkService fileChunkService;

    public DocPreprocess(@Autowired DocumentParserService documentParserService,
                         @Autowired FileChunkService fileChunkService) {
        this.documentParserService = documentParserService;
        this.fileChunkService = fileChunkService;
    }

    /**
     * Java从指定目录下提取所有文档
     */
    @Test
    @Order(1)
    public void docParser() {
        // 输入目录
        String inputDir = "src/main/resources/documents";
        // 输出目录
        String outputDir = "src/main/resources/data/test.json";
        documentParserService.DocParser(inputDir, outputDir);
    }

    /**
     * 将提取的文档进行分块
     */
    @Test
    @Order(2)
    public void fileChunk() {
        // 输入目录
        String inputPath = "src/main/resources/data/test.json";
        // 输出目录
        String outputPath = "src/main/resources/data/chunk.json";
        fileChunkService.FileChunk(inputPath, outputPath);
    }
}
