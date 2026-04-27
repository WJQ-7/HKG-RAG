package com.bistu.hkgrag.preprocess.clean;


import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

import static com.bistu.hkgrag.preprocess.utils.TextUtils.chunk;
import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJson;

/**
 * 对清洗后的文件划分chunk 设置最大分块为4096个token
 * 若文件小于4096个token则不需要分块，若大于则分块
 * @author wangjingqi
 */
@Service
public class FileChunkService {
    private static final Logger log = LoggerFactory.getLogger(FileChunkService.class);
    //最大分块
    @Value("${HKG-RAG.chunk.max-size}")
    int max_chunk_size;
    //重叠token
    @Value("${HKG-RAG.chunk.overlap-chunk-size}")
    int overlap_chunk_size;
    public void FileChunk(String inputPath, String outputPath) {
        try{
            List<DoFile> documentItemList=readJson(inputPath);
            List<DoFile> docList= chunk(documentItemList,max_chunk_size,overlap_chunk_size);
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(new File(outputPath), docList);
            log.info("获取文件分片成功,[{}]个文档分为[{}]片,分片结果输出在[{}]",documentItemList.size(),docList.size(),outputPath);

        } catch (Exception e) {
            log.error("获取文件分片失败", e);
            e.printStackTrace();
        }
    }
}
