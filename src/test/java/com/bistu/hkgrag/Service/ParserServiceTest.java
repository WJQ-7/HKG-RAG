package com.bistu.hkgrag.Service;

import com.bistu.hkgrag.preprocess.parser.DocParserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class ParserServiceTest {
    private DocParserService docParserService;

    public ParserServiceTest(@Autowired DocParserService docParserService) {
        this.docParserService = docParserService;
    }

    @Test
    public void test_DocParser(){

        String inputPath = "D:\\LLMprojects\\HKG-RAG\\src\\main\\resources\\documents";
        String outputPath = "D:\\LLMprojects\\HKG-RAG\\src\\main\\resources\\data\\test.json";

        docParserService.extract(inputPath, outputPath);

    }


}
