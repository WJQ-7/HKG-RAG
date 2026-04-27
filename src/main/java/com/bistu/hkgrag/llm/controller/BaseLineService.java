package com.bistu.hkgrag.llm.controller;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class BaseLineService {
    Logger log = LoggerFactory.getLogger(BaseLineService.class);
    private FileService fileService;
    private ChatClient chatClient;

    @Value("${HKG-RAG.retrieval.TopK}")
    int topK;

    public BaseLineService(@Autowired FileService fileService,
                           @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient) {
        this.fileService = fileService;
        this.chatClient = chatClient;
    }

    public String baselineRAG(String question) {
        try {
            List<DoFile> fileList = fileService.vectorSearchByContent(question, topK);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileList.size(); i++) {
                DoFile file = fileList.get(i);
                sb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(file.getFileName()).append("\n")
                        .append("文档内容：").append(file.getContent()).append("\n\n");
            }
            String prompt = """
                    #你是一个专业的文档问答助手。
                    
                    #任务：根据检索到的文档内容回答用户问题
                    
                    #要求
                    1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
                    2. 如果文档中没有相关答案，请直接回复：“根据提供的文档，未找到相关信息。”
                    3. 回答语言要简洁、准确、通顺，符合用户问题的要求。
                    4. 禁止出现与文档无关的内容、猜测性表述或主观评论。
                    
                    #输入：
                    用户问题：%s
                    相关文档：%s
                    """.formatted(question, sb.toString());
            String response = chatClient.prompt(prompt).call().content();
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("基线方法回答失败", e);
            return null;
        }


    }
}