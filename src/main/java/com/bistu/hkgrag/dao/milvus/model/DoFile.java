package com.bistu.hkgrag.dao.milvus.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Milvus向量库中file表
 * @author wangjingqi
 */
@Setter
@Getter
@NoArgsConstructor
public class DoFile {

    /**
     * 标识文档
     */
    public String fileId;
    /**
     * 标识分片
     */
    public String chunkId;
    /**
     * 文件名
     */
    public String fileName;
    /**
     * 分片主题
     */
    public String topic;
    /**
     * 文件内容
     */
    public String content;
    /**
     * 主题向量
     */
    public float[] topicVector;
    /**
     * 内容向量
     */
    public float[] contentVector;
    /**
     * 话题相似度
     */
    public float topicScore;
    /**
     * 内容相似度
     */
    public float contentScore;



    public DoFile(String fileId, String chunkId, String fileName, String content) {
        this.fileId = fileId;
        this.chunkId = chunkId;
        this.fileName = fileName;
        this.content = content;
    }
    public DoFile(String fileId, String chunkId, String fileName,String topic, String content) {
        this.fileId = fileId;
        this.chunkId = chunkId;
        this.fileName = fileName;
        this.topic = topic;
        this.content = content;
    }

}
