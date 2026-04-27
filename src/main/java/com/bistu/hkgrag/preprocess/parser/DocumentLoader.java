package com.bistu.hkgrag.preprocess.parser;


import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * 文件加载，递归获取指定文件目录下的所有文件并提取信息
 * @author wangjingqi
 */
public class DocumentLoader {
    // 返回：key = 文件路径，value = 文本内容
    public static Map<String, String> loadDocuments(String rootPath) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        File root = new File(rootPath);
        process(root, "", result);
        return result;
    }

    // 递归处理目录、文件、ZIP
    private static void process(File file, String prefix, Map<String, String> result) throws Exception {

        if (file.isDirectory()) { // 文件夹
            for (File f : Objects.requireNonNull(file.listFiles())) {
                process(f, prefix + file.getName() + "/", result);
            }
            return;
        }
        System.out.println("文件夹：" + file.getName());

        // ZIP 文件
        if (file.getName().toLowerCase().endsWith(".zip")) {
            processZip(file, prefix, result);
            return;
        }
        //rar压缩文件
        if (file.getName().toLowerCase().endsWith(".rar")) {
            processRar(file, prefix, result);
            return;
        }
        // 普通文件
        byte[] bytes = Files.readAllBytes(file.toPath());
        //String text = TextExtractor.extractText(file.getName(), bytes);
        String text=MarkDownExtractor.extractMarkdown(file.getName(),bytes);
        if (text != null && !text.trim().isEmpty()) {
            // 只有当内容有效时才写入结果 Map
            result.put(prefix + file.getName(), text);
        } else {
            String  relativeFileName=prefix + file.getName();
            // 记录一条日志，表明该文件被跳过
            System.out.println("警告：文件 [" + relativeFileName + "] 无法提取有效内容，已跳过。");
        }
    }

    // 解析 ZIP（支持 ZIP 嵌套）
    private static void processZip(File zipFile, String prefix, Map<String, String> result) throws Exception {

        String zipFileName = zipFile.getName();
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new FileInputStream(zipFile), "GBK", false)) { // 指定 GBK 读取中文
            ZipArchiveEntry entry;

            while ((entry = zis.getNextZipEntry()) != null) {
                if (entry.isDirectory()) continue;
                // 统一路径分隔符
                String fileName = entry.getName().replace("\\", "/");
                // 去掉 ZIP 内顶级目录（可选）
                String[] parts = fileName.split("/", 2);
                String relativeFileName = parts.length == 2 ? parts[1] : parts[0];
                String baseName = zipFile.getName().replaceAll("\\.zip$", "");
                String path = prefix + baseName + "/" + relativeFileName;
                // 读取文件内容到 byte[]
                byte[] bytes = zis.readAllBytes();
                // ZIP in ZIP
                if (relativeFileName.toLowerCase().endsWith(".zip")) {
                    File temp = Files.createTempFile("nested_", ".zip").toFile();
                    Files.write(temp.toPath(), bytes);
                    processZip(temp, prefix + baseName + "/", result);
                    temp.delete();
                } else {
                    // 普通文件
                    //String text = TextExtractor.extractText(relativeFileName, bytes);
                    String text=MarkDownExtractor.extractMarkdown(relativeFileName,bytes);
                    if (text != null && !text.trim().isEmpty()) {
                        result.put(path, text);
                    }else{
                        System.out.println("文件 [" + relativeFileName + "] 无法提取有效内容，已跳过。");
                    }

                }
            }
        }catch (IOException e) {
            // 捕获 ZIP 文件头部或结构读取时的 Truncated ZIP file 异常
            if (e.getMessage() != null && e.getMessage().contains("Truncated ZIP file")) {
                System.err.println("错误：ZIP 文件 [" + zipFileName + "] 结构损坏或未下载完整，已跳过整个文件。");
                return; // 跳过整个 ZIP 文件
            }
            // 重新抛出其他错误
            throw new RuntimeException("处理 ZIP 文件时发生意外错误: " + zipFileName, e);
        }
    }

    private static void processRar(File rarFile, String prefix, Map<String, String> result) throws Exception {
        try (Archive archive = new Archive(rarFile)) {
            if (archive == null) {
                System.err.println("无法打开 RAR 文件：" + rarFile.getAbsolutePath());
                return;
            }
            FileHeader header;
            //遍历 RAR 文件里的每个文件头
            while ((header = archive.nextFileHeader()) != null) {
                if (header.isDirectory()) continue;
                //跳过文件夹，只处理文件
                String fileName = header.getFileNameW().trim();
                String normalizedFileName = fileName.replace("\\", "/"); // 统一斜杠
                String[] parts = normalizedFileName.split("/", 2);       // 分割成两部分
                String relativeFileName = parts.length == 2 ? parts[1] : parts[0];
                String path = prefix + rarFile.getName() + "/" + relativeFileName;
                // 读取文件内容到 byte[]
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                archive.extractFile(header, baos);
                byte[] bytes = baos.toByteArray();
                String lower = fileName.toLowerCase();
              //  String text = TextExtractor.extractText(fileName, bytes);
                try {
                    String text = MarkDownExtractor.extractMarkdown(fileName, bytes);
                    result.put(path, text);
                } catch (Exception e) {
                    System.err.println("跳过无法解析的文件：" + path + "，原因：" + e.getMessage());
                }

            }
        }catch (com.github.junrar.exception.CorruptHeaderException e) {
            // 捕获文件头损坏异常
            System.err.println("警告：RAR 文件 [" + rarFile.getName() + "] 文件头损坏，已跳过解析。");
            // 可以选择记录更详细的日志 e.getMessage()
            return; // 跳过这个 RAR 文件，继续处理下一个文件
        } catch (Exception e) {
            // 捕获其他所有异常
            throw new RuntimeException("处理 RAR 文件 [" + rarFile.getName() + "] 时发生错误。", e);
        }
    }

}
