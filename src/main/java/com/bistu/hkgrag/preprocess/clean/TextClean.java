package com.bistu.hkgrag.preprocess.clean;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档清洗类
 * 对收集得文档内容做清洗，保留文字去除不可见得字符、清理异常符号、统一换行形式、去除重复空行、去除行尾空白
 */
public class TextClean {
    private static final Logger log = LoggerFactory.getLogger(TextClean.class);
    // 保留中文、英文、数字、常见符号、换行、制表等
    private static final Pattern CLEAN_PATTERN = Pattern.compile(
            "[^\\p{IsHan}" +        // 中文汉字
                    "A-Za-z0-9" +            // 英文和数字
                    "，。？！；：、~\\-—_()（）\\[\\]{}<>“”\"'·•…:;,.?！!@#¥$%&*+=/\\\\\\s]" // 常用标点符号和空白
    );


    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        try{
            String inputPath = "src/main/resources/data/parser.json";     // 输入目录
            String outputPath = "src/main/resources/data/clean.json";  // 输出目录

            cleanSingleJsonFile(new File(inputPath),new File(outputPath) );
            log.info("文档清洗成功，清洗结果输出[{}]",outputPath);
        } catch (Exception e) {
            log.error("文档清洗失败",e);
            e.printStackTrace();
        }

    }

    public static void cleanSingleJsonFile(File inFile, File outFile) throws IOException {
        System.out.println("准备清洗文件: " + inFile.getName());
        String jsonText = Files.readString(inFile.toPath(), StandardCharsets.UTF_8);
        List<DoFile> list = new ArrayList<>();
        // 将字符串解析为 JSONArray
        JSONArray jsonArray = new JSONArray(jsonText);
        // 循环获取每个对象的 fileName 和 content
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject obj = jsonArray.getJSONObject(i);
            String fileId = obj.getString("fileId");
            String chunkId = obj.getString("chunkId");
            String fileName = obj.optString("fileName"); // 可以避免 null
            String content = obj.optString("content");
            String cleanedContent = cleanContent(content);
            list.add(new DoFile(fileId,chunkId,fileName,cleanedContent));
        }

        // 输出 JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outFile, list);

    }


    /**
     * 清洗函数,去除空行及异常符号，保留具有意义的文字内容
     */
    public static String cleanContent(String text) {
        if (text == null) return "";

        // 1. 彻底删除不可见控制字符（不影响表格）
        text = text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");

        // 2. 清理乱码、特殊符号（不删空格、不删表格符号）
        text = text.replaceAll("[•■□►◆◇★☆○●◎※]", "");

        // 3. 全角空格 / 不间断空格 → 普通空格（保留空格，不删除）
        text = text.replaceAll("[\\u00A0\\u3000\\u2007\\u202F]", " ");

        // 4. 统一换行（保留换行，不破坏行结构）
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        // 5. 只清理【完全空白的空行】，不删除表格行之间的换行
        text = text.replaceAll("^\\s+", ""); // 开头空行
        text = text.replaceAll("\\s+$", ""); // 结尾空行

        // 6. 连续空行最多保留 1 行（避免太松散）
        text = text.replaceAll("\n{3,}", "\n\n");

        // 7. 【关键】不压缩多空格！不删中文之间空格！保留表格对齐空格！

        // 8. 仅删除【行尾】多余空格（不影响表格）
        text = text.replaceAll("[ \\t]+\\n", "\n");

        // 9. 清理异常长横线（不影响正常短横线）
        text = text.replaceAll("-{4,}", "");

        return text;
    }
}
