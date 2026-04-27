package com.bistu.hkgrag.preprocess.parser;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Service
public class DocParserService {
    public void extract(String inputDir, String outputJson) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "D:/LLMprojects/GMM/.venv/Scripts/python.exe",
                    "D:/LLMprojects/GMM/Extraction/test.py",
                    inputDir,
                    outputJson
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取日志（可选）
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[PYTHON] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Python执行失败");
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
