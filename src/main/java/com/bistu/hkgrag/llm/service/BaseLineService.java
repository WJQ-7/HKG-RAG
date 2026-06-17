package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.llm.model.agent.RagResult;
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
    public String prompt="""
                    #你是一个专业的文档问答助手。
                    
                    #任务：根据检索到的文档内容回答用户问题
                    
                    #要求
                    1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
                    2. 如果文档中没有相关答案，请直接回复：“根据提供的文档，未找到相关信息。”
                    
                    #输入：
                    用户问题：%s
                    相关文档：%s
                    """;

    /**
     * 基于内容向量的bm25来检索生成
     * @param question 待检索的用户问题
     * @return 基于bm25检索结果的回答
     */
    public RagResult bm25Rag(String question){
        try {
            List<DoFile> fileList = fileService.bm25SearchByContent(question, topK);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileList.size(); i++) {
                DoFile file = fileList.get(i);
                sb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(file.getFileName()).append("\n")
                        .append("文档内容：").append(file.getContent()).append("\n\n");
            }
            String userPrompt = prompt.formatted(question, sb.toString());
            String response = chatClient.prompt(userPrompt).call().content();
            RagResult ragResult = new RagResult();
            ragResult.setAnswer(response);
            ragResult.setFiles(fileList);
            return ragResult;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("bm25Rag回答失败", e);
            return null;
        }
    }

    /**
     * 向量检索
     * @param question
     * @return
     */
    public RagResult naiveRAG(String question) {
        try {
            List<DoFile> fileList = fileService.vectorSearchByContent(question, topK);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileList.size(); i++) {
                DoFile file = fileList.get(i);
                sb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(file.getFileName()).append("\n")
                        .append("文档内容：").append(file.getContent()).append("\n\n");
            }
            String userPrompt = prompt.formatted(question, sb.toString());
            String response = chatClient.prompt(userPrompt).call().content();
            RagResult ragResult = new RagResult();
            ragResult.setAnswer(response);
            ragResult.setFiles(fileList);
            return ragResult;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("NaiveRag回答失败", e);
            return null;
        }
    }

    /**
     * bm25+向量检索RAG
     * @param question 用户问题
     * @return 基于bm25+向量检索到的结果回答问题
     */
    public RagResult hybridRAG(String question) {
        try {
            List<DoFile> fileList = fileService.hybridSearchByContent(question, topK);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fileList.size(); i++) {
                DoFile file = fileList.get(i);
                sb.append("相关文档").append(i + 1).append("：\n")
                        .append("文档名：").append(file.getFileName()).append("\n")
                        .append("文档内容：").append(file.getContent()).append("\n\n");
            }
            String userPrompt = prompt.formatted(question, sb.toString());
            String response = chatClient.prompt(userPrompt).call().content();
            RagResult ragResult = new RagResult();
            ragResult.setAnswer(response);
            ragResult.setFiles(fileList);
            return ragResult;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("hybridRAG回答失败", e);
            return null;
        }
    }
}