package com.bistu.hkgrag.preprocess.utils;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.IntArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

public class TextUtils {
    private static final Logger log = LoggerFactory.getLogger(TextUtils.class);
    // 定义token的模型
    private static final Encoding ENCODER;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODER = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * 读取指定json文件
     *
     * @param filePath 文件路径
     * @return list
     */
    public static List<DoFile> readJson(String filePath) {
        // 1. 空路径校验（提前拦截无效参数）
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("JSON文件路径不能为空！");
            return new ArrayList<>(); // 返回空集合，避免调用方空指针
        }
        File jsonFile = new File(filePath);
        // 2. 文件存在性校验
        if (!jsonFile.exists()) {
            log.error("JSON文件不存在！路径：{}", filePath);
            return new ArrayList<>();
        }
        // 3. 避免文件是目录
        if (jsonFile.isDirectory()) {
            log.error("路径指向的是目录，不是文件！路径：{}", filePath);
            return new ArrayList<>();
        }
        try {
            String jsonText = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            // 4. 空文件校验（避免解析空字符串报错）
            if (jsonText == null || jsonText.trim().isEmpty()) {
                log.warn("JSON文件内容为空！路径：{}", filePath);
                return new ArrayList<>();
            }

            List<DoFile> list = new ArrayList<>();
            // 将字符串解析为 JSONArray
            JSONArray jsonArray = new JSONArray(jsonText);
            // 循环获取每个对象的 fileName 和 content
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String chunkId = obj.getString("chunkId");
                String fileId = obj.getString("fileId");
                String fileName = obj.optString("fileName");
                String content = obj.optString("content");
                list.add(new DoFile(fileId, chunkId, fileName,content));
            //    list.add(new DoFile(fileName,content));
            }
            return list;
        } catch (Exception e) {
            log.error("读取json文件失败", e);
            e.printStackTrace();
        }
        return new ArrayList<>();
    }


    /**
     * 将 JSONL 文件读取为指定类型的对象列表（泛型方法）
     */
    public static <T> List<T> readJsonl(String filePath, Class<T> clazz) {
        List<T> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                T obj = objectMapper.readValue(line, clazz);
                result.add(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("读取 JSONL 文件失败: {}", filePath);
        }
        return result;
    }

//    /**
//     * 将指定jsonl文件映射成DoFile
//     * @param filePath 指定文件路径
//     * @return 列表
//     */
//    public static List<DoFile> readJsonlToDoFile(String filePath) {
//        List<DoFile> fileList = new ArrayList<>();
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
//            String line;
//            while ((line = br.readLine()) != null) {
//                DoFile file = objectMapper.readValue(line, DoFile.class);
//                fileList.add(file);
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("读取指定目录下的Jsonl文件映射成DoFile失败");
//        }
//        return fileList;
//    }

    /**
     * 通用读取 jsonl 文件，返回指定类型的 List
     * @param filePath 文件路径
     * @param clazz 目标类
     * @return List<T>
     */
    public static <T> List<T> readJsonlToList(String filePath, Class<T> clazz) {
        List<T> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                T obj = objectMapper.readValue(line, clazz);
                list.add(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("读取jsonl文件失败，path: {}", filePath);
        }
        return list;
    }

    /**
     * 追加写入 JSONL （不会覆盖原有内容）
     */
    public static <T> void writeJsonlAppend(String path, List<T> list) {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(path), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (T obj : list) {
                writer.write(new ObjectMapper().writeValueAsString(obj));
                writer.newLine();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 将对象列表写入 JSONL 文件
     *
     * @param outputPath 输出文件路径
     * @param dataList   (List<Object>) 任意对象列表
     */
    public static void writeJsonl(String outputPath, List<?> dataList) {
        // 参数校验
        if (outputPath == null || outputPath.trim().isEmpty()) {
            log.error("输出 JSONL 路径不能为空！");
            return;
        }
        if (dataList == null || dataList.isEmpty()) {
            log.warn("写入 JSONL 时 dataList 为空：{}", outputPath);
            return;
        }
        File outFile = new File(outputPath);
        // 确保父目录存在
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (!created) {
                log.warn("无法创建输出目录：{}", parent.getAbsolutePath());
            }
        }
        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(outFile, StandardCharsets.UTF_8, false))) {
            for (Object obj : dataList) {
                try {
                    // 转为 JSON 字符串
                    String json = objectMapper.writeValueAsString(obj);
                    writer.write(json);
                    writer.newLine();
                } catch (JsonProcessingException e) {
                    log.error("序列化对象为 JSON 失败：{}", obj, e);
                }
            }
            log.info("JSONL 写入完成：{}", outputPath);
        } catch (Exception e) {
            log.error("写入 JSONL 文件失败：{}", outputPath, e);
        }
    }

    /**
     * 切分文本chunk
     * @param docList 文件列表
     * @param max_chunk_size 最大chunk
     * @param overlap_chunk_size 重叠chunk大小
     * @return 返回分块列表
     */
    public static List<DoFile> chunk(List<DoFile> docList, int max_chunk_size, int overlap_chunk_size) {
        /**
         * 1.计算fileId 文档名字的hash值
         * 2.计算单条文档内容的token数量
         *  若token数小于chunk长度则无需进行分块，若大于则进行分块，分片采用滑动重叠分块
         */
        if (docList.isEmpty()) {
            log.info("文件列表为空");
            return new ArrayList<>();
        }
        List<DoFile> chunklist = new ArrayList<>();
        try {
            for (DoFile item : docList) {
                //计算fileId 文档名字的hash值
                MessageDigest md = MessageDigest.getInstance("MD5");
                String fullText = "文件名：" + item.getFileName() + "\n" + item.getContent();
                // 编码带文件名的完整文本
                IntArrayList encodedContent = ENCODER.encode(fullText);

                int totalToken = encodedContent.size();
                int step = max_chunk_size - overlap_chunk_size;
                int count = 0;
                if (totalToken > max_chunk_size) {
                    for (int start = 0; start < totalToken; start += step) {
                        // 计算当前分块的结束索引（不超过总Token数）
                        int end = Math.min(start + max_chunk_size, totalToken);
                        int[] subArray = Arrays.copyOfRange(encodedContent.toArray(), start, end);
                        IntArrayList chunkTokens = new IntArrayList(subArray.length); // 指定初始容量
                        for (int token : subArray) {
                            chunkTokens.add(token);
                        }
                        count++;
                        String chunkContent = ENCODER.decode(chunkTokens);
                        DoFile doFile = new DoFile();
                        doFile.setFileName(item.getFileName());
                        doFile.setTopic("");
                        doFile.setContent(chunkContent);
                        doFile.setFileId(item.getFileId());
                        //chunkId为内容得哈希值 为了每个chunkId是唯一得
                        byte[] temp_chunkId = md.digest(chunkContent.getBytes(StandardCharsets.UTF_8));
                        HexFormat hexFormat = HexFormat.of();
                        doFile.setChunkId(hexFormat.formatHex(temp_chunkId).toUpperCase());
                        chunklist.add(doFile);
                    }
                } else {
                    byte[] temp_chunkId = md.digest(item.getContent().getBytes(StandardCharsets.UTF_8));
                    HexFormat hexFormat = HexFormat.of();
                    item.setChunkId(hexFormat.formatHex(temp_chunkId).toUpperCase());
                    item.setTopic("");
                    chunklist.add(item);
                }
            }
            return chunklist;
        } catch (Exception e) {
            log.error("获取文件切片失败", e);
            e.printStackTrace();
            return new ArrayList<>();
        }

    }

    public static void printProgress(int current, int total, String taskName) {
        int width = 40;
        double percentage = (double) current / total;
        int complete = (int) (width * percentage);

        String bar = "▋".repeat(complete) + "-".repeat(width - complete);

        String progress = String.format(
                "\r%-80s",  // ⭐ 关键：补空格覆盖残留字符
                String.format("%s: [%s] %3d%% (%d/%d)",
                        taskName, bar, (int)(percentage * 100), current, total)
        );

        System.out.print(progress);
        System.out.flush();
    }

}
