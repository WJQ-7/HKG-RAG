package com.bistu.hkgrag.preprocess.parser;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * 将指定文件夹下得所有目录中得文件都提取成json格式
 */
@Service
public class DocumentParserService {
    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    public void DocParser(String inputDir,String outputPath){
        try{
            // 递归加载文档
            Map<String, String> map = DocumentLoader.loadDocuments(inputDir);
            log.info("文档总数[{}]", map.size());
            // 构建 JSON 对象列表
            List<DoFile> list = new ArrayList<>();
            for (Map.Entry<String, String> e : map.entrySet()) {
                // 只保留文件名
                String fileId = UUID.randomUUID().toString();
                String fileName = new File(e.getKey()).getName();
                if(e.getValue()!=null && !e.getValue().isEmpty()){
                    list.add(new DoFile(fileId, "", fileName, e.getValue()));
                }
            }

            // ✅ 取前2000个
            if (list.size() > 1000) {
                list = list.subList(0, 3);
            }
            // 输出 JSON
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(outputPath), list);
            log.info("解析完成，输出 JSON路径为：{}" , outputPath);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("提取文档失败"+e.getMessage());
        }


    }

}
