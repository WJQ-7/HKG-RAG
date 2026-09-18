package com.bistu.hkgrag.Agent;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootTest(classes = com.bistu.hkgrag.HkgRagApplication.class)
public class JsonlTest {
    @Test
    public void testJsonl() {

        try {
            ObjectMapper mapper = new ObjectMapper();

            String inputPath = "src/main/resources/data/qa_test_set.jsonl";
            String outputPath = "src/main/resources/data/BISTU-OfficeQA.jsonl";

            String content = Files.readString(Paths.get(inputPath));

            // 用 }{ 切分多个 JSON 对象
            String[] parts = content.split("\\}\\s*\\{");

            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath))) {

                for (int i = 0; i < parts.length; i++) {

                    String json;

                    if (i == 0) {
                        json = parts[i] + "}";
                    } else if (i == parts.length - 1) {
                        json = "{" + parts[i];
                    } else {
                        json = "{" + parts[i] + "}";
                    }

                    try {
                        Object node = mapper.readValue(json, Object.class);
                        writer.write(mapper.writeValueAsString(node));
                        writer.newLine();
                    } catch (Exception e) {
                        System.out.println("解析失败 JSON: " + json);
                        e.printStackTrace();
                    }
                }
            }

            System.out.println("JSONL 转换完成");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
