package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.config.PromptConfig;
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
    private PromptConfig promptConfig;

    @Value("${HKG-RAG.retrieval.TopK}")
    int topK;

    public BaseLineService(@Autowired FileService fileService,
                           @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient,
                           @Autowired PromptConfig promptConfig) {
        this.fileService = fileService;
        this.chatClient = chatClient;
        this.promptConfig = promptConfig;
    }

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
            String userPrompt = promptConfig.baselinePrompt().formatted(question, sb.toString());
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
            String userPrompt = promptConfig.baselinePrompt().formatted(question, sb.toString());
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
            String userPrompt = promptConfig.baselinePrompt().formatted(question, sb.toString());
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