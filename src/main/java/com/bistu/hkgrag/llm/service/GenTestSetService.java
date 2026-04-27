package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.model.agent.TestSetListResult;
import com.bistu.hkgrag.llm.model.agent.TestSetResult;
import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.bistu.hkgrag.preprocess.utils.TextUtils.readJson;

@Service
public class GenTestSetService {
    private Logger log = LoggerFactory.getLogger(GenTestSetService.class);
    private final ObjectMapper objectMapper;
    // 最终输出 100 题：30单跳 + 30同文档两跳 + 40跨文档
    private static final int TOTAL_QUESTIONS = 100;
    private static final int SINGLE_HOP = 30;
    private static final int SAME_DOC_Multi_HOP = 30;
    private static final int CROSS_DOC = 40;

    // 从500篇里只随机选 20 篇给模型生成问题 ✅
    private static final int SAMPLE_DOC_COUNT = 20;
    private final ChatClient chatClient;

    public GenTestSetService(ObjectMapper objectMapper, @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient) {
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    public void GenTestSet(String inputPath, String outputPath) {

        List<TestSet> testSets = new ArrayList<>();
        try {
            List<DoFile> docs = readJson(inputPath);
            log.info("读取文档总数：{}", docs.size());
            Map<String, List<DoFile>> fileGroupMap = docs.stream()
                    .collect(Collectors.groupingBy(DoFile::getFileId));
            List<String> fileIds = new ArrayList<>(fileGroupMap.keySet());
            Collections.shuffle(fileIds);
            Random random = new Random();

            // ========= 1. 单跳 =========
            BeanOutputConverter<TestSetListResult> testSetConverter = new BeanOutputConverter<>(TestSetListResult.class);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(0.1)
                    .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, testSetConverter.getJsonSchema()))
                    .build();
            //单跳问题计数
            int singleCount = 0;
            for (int i = 0; i < SINGLE_HOP; i++) {
                //随机选一个 fileId
                String fileId = fileIds.get(random.nextInt(fileIds.size()));
                //获取随机id下的文档
                List<DoFile> group = fileGroupMap.get(fileId);
                //将文档拼接成字符串
                String fullContent = group.stream().map(DoFile::getContent).collect(Collectors.joining(""));
                String single_Hop_prompt = String.format(PromptTemplates.Generation_SingleHop, group.get(0).getFileName(),fullContent);

                TestSetListResult singleHopList = chatClient.prompt()
                        .user(single_Hop_prompt)
                        .options(options)
                        .call()
                        .entity(TestSetListResult.class);

                // 2. 手动转 List<TestSetResult>

                for (TestSetResult item : singleHopList.testSetResultList()) {
                    TestSet testSet = new TestSet();
                    testSet.setFileId(fileId);
                    testSet.setType(TestSet.Type.SINGLE_HOP);
                    testSet.setQuestion(item.question());
                    testSet.setAnswer(item.answer());
                    testSet.setAccording(item.according());
                    testSets.add(testSet);
                    singleCount++;
                }

                log.info("单跳进度：{}% ({}/{})", (i + 1) * 100 / SINGLE_HOP, i + 1, SINGLE_HOP);

            }
            log.info("单跳问题答案对已经生成完成，单跳问题的个数：{}", singleCount);

            // ========= 2. 同文档多跳 =========
            int multiCount = 0;
            for (int i = 0; i < SAME_DOC_Multi_HOP; i++) {
                // 随机选一个 fileId（同文档，保证上下文关联）
                String fileId = fileIds.get(random.nextInt(fileIds.size()));
                List<DoFile> group = fileGroupMap.get(fileId);
                String fullContent = group.stream().map(DoFile::getContent).collect(Collectors.joining(""));
                // 多跳提示词
                String multi_Hop_Prompt = String.format(PromptTemplates.SimpleDoc_MultiHop, group.get(0).getFileName(),fullContent);
                // 调用大模型生成多跳问答对
                TestSetListResult multiHopList = chatClient.prompt()
                        .user(multi_Hop_Prompt)
                        .options(options)
                        .call()
                        .entity(TestSetListResult.class);

                for (TestSetResult item : multiHopList.testSetResultList()) {
                    TestSet testSet = new TestSet();
                    testSet.setFileId(fileId);
                    testSet.setQuestion(item.question());
                    testSet.setAnswer(item.answer());
                    testSet.setAccording(item.according());
                    testSet.setType(TestSet.Type.SAME_DOC_Multi_HOP);
                    testSets.add(testSet);
                    multiCount++;
                }

                log.info("多跳进度：{}% ({}/{})", (i + 1) * 100 / SAME_DOC_Multi_HOP, i + 1, SAME_DOC_Multi_HOP);
                TextUtils.printProgress(i + 1, SAME_DOC_Multi_HOP, "多跳生成进度");

            }
            log.info("同文档多跳问题答案对已经生成完成，同文档多跳问题的个数为：{}",multiCount);
            log.info("总数：{}", singleCount+multiCount);


//            // ========= 3. 跨2文档 =========
//            for (int i = 0; i < 30; i++) {
//                List<DoFile> pair = pickDocs(docs, docUsage, 2);
//
//                generateCross2(pair, testSet);
//
//                updateUsage(pair, docUsage);
//            }
//
//            // ========= 4. 跨3文档 =========
//            for (int i = 0; i < 30; i++) {
//                List<DoFile> group = pickDocs(docs, docUsage, 3);
//
//                generateCross3(group, testSet);
//
//                updateUsage(group, docUsage);
//            }


            // 写文件
            TextUtils.writeJsonl(outputPath, testSets);


        } catch (Exception e) {
            e.printStackTrace();
            log.error("生成测试集失败", e.getMessage());
        }


    }


}
