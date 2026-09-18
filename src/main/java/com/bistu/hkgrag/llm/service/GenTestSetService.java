package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.config.PromptConfig;
import com.bistu.hkgrag.dao.milvus.model.DoCluster;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.milvus.service.FileService;
import com.bistu.hkgrag.dao.neo4j.service.Neo4jService;
import com.bistu.hkgrag.llm.model.agent.TestSet;
import com.bistu.hkgrag.llm.model.agent.TestSetListResult;
import com.bistu.hkgrag.llm.model.agent.TestSetResult;
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
    //单跳问题数量
    private static final int SINGLE_HOP = 30;
    //同文档多跳问题数量
    private static final int SAME_DOC_Multi_HOP = 30;
    //跨文档问题数量
    private static final int CROSS_DOC_Multi_HOP = 40;
    //主题级问题数量
    private static final int SUMMARY_LEVEL = 20;

    // 从500篇里只随机选 20 篇给模型生成问题 ✅
    private static final int SAMPLE_DOC_COUNT = 20;
    private final ChatClient chatClient;
    private final Neo4jService neo4jService;
    private final FileService fileService;
    private final PromptConfig promptConfig;

    public GenTestSetService(ObjectMapper objectMapper,
                             @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient,
                             @Autowired Neo4jService neo4jService,
                             @Autowired GenClusterService clusterService,
                             @Autowired FileService fileService,
                             @Autowired PromptConfig promptConfig) {
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
        this.neo4jService = neo4jService;
        this.fileService = fileService;
        this.promptConfig = promptConfig;
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
                String single_Hop_prompt = String.format(promptConfig.generationSingleHop(), group.get(0).getFileName(),fullContent);

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
                String multi_Hop_Prompt = String.format(promptConfig.generationSimpleDocMultiHop(), group.get(0).getFileName(),fullContent);
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


//            // ========= 3. 跨文档 =========
            //1.获取所有聚类节点id
            List<Map<String, String>> clusterList = neo4jService.findNodeByLabel("主题聚类节点");
            List<String> idList = new ArrayList<>();
            for (Map<String, String> map : clusterList) {
                String id = map.get("id");
                idList.add(id);
            }
            //2.获取所有聚类节点相连的文档节点
            List<Map<String, Object>> result = new ArrayList<>();
            for (String id : idList) {
                List<Map<String, Object>> fileList = neo4jService.findFileById(id);
                if (fileList.size() > 1) {
                    // 把关系数据 → 提取出 end（文档节点）
                    List<Map<String, Object>> docNodeList = new ArrayList<>();
                    for (Map<String, Object> relationMap : fileList) {
                        // 从关系里取出 文档节点 end
                        Map<String, Object> docNode = (Map<String, Object>) relationMap.get("end");
                        docNodeList.add(docNode);
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("clusterId", id);
                    item.put("documentCount", fileList.size());
                    item.put("documentList", docNodeList); // 这里存纯文档节点！
                    result.add(item);
                }
            }
            int crossDosCount = 0;
            for (int i = 0; i < Math.min(CROSS_DOC_Multi_HOP, result.size()); i++) {
                int randomIndex = random.nextInt(result.size());
                Map<String, Object> randomCluster = result.remove(randomIndex);
                // 获取随机id下的所有文档
                List<Map<String, String>> docList = (List<Map<String, String>>) randomCluster.get("documentList");
                List<String> fileIdList = new ArrayList<>();
                if (docList.size() == 2) {
                    fileIdList.add(docList.get(0).get("id"));
                    fileIdList.add(docList.get(1).get("id"));
                }
                // 如果超过 2 个，再随机取两个不同的
                else {
                    int index1 = random.nextInt(docList.size());
                    int index2 = random.nextInt(docList.size());

                    // 防止重复
                    while (index1 == index2) {
                        index2 = random.nextInt(docList.size());
                    }
                    fileIdList.add(docList.get(index1).get("id"));
                    fileIdList.add(docList.get(index2).get("id"));
                }
                List<DoFile> fileList = fileService.getFilesById(fileIdList);
                StringBuilder promptSb = new StringBuilder();
                for (int j = 0; j < fileList.size(); j++) {
                    promptSb.append("文档").append(j + 1).append("：")
                            .append(fileList.get(j).getContent())
                            .append("\n\n");
                }
                String Cross_Dos_Prompt = String.format(promptConfig.generationCrossDoc(), promptSb.toString());

                //调用大模型生成多跳问答对
                TestSetListResult crossDosList = chatClient.prompt()
                        .user(Cross_Dos_Prompt)
                        .options(options)
                        .call()
                        .entity(TestSetListResult.class);

                for (TestSetResult item : crossDosList.testSetResultList()) {
                    TestSet testSet = new TestSet();
                    String fileId = fileList.stream()
                            .map(DoFile::getFileId)  // 提取 id
                            .map(String::valueOf) // 转字符串
                            .collect(Collectors.joining(","));
                    testSet.setFileId(fileId);
                    testSet.setQuestion(item.question());
                    testSet.setAnswer(item.answer());
                    testSet.setAccording(item.according());
                    testSet.setType(TestSet.Type.CROSS_DOC_Multi_HOP);
                    testSets.add(testSet);
                    crossDosCount++;
                }

                log.info("多跳进度：{}% ({}/{})", (i + 1) * 100 / CROSS_DOC_Multi_HOP, i + 1, CROSS_DOC_Multi_HOP);
                TextUtils.printProgress(i + 1, CROSS_DOC_Multi_HOP, "多跳生成进度");

            }
            log.info("跨文档问题答案对已经生成完成，跨文档问题的个数为：{}", crossDosCount);
            // log.info("总数：{}", singleCount+multiCount+crossDosCount);

            // ========= 4.主题级（Topic-Level）问题生成 =========
            int topicLevelCount = 0;
            // 1. 获取所有主题聚类节点
            List<Map<String, String>> clusterNodeList = neo4jService.findNodeByLabel("主题聚类节点");
            GeneratedQuestions topicResult = new GeneratedQuestions();
            log.info("获取到有效主题数量：{}", clusterNodeList.size());
            for (int i = 0; i < Math.min(SUMMARY_LEVEL, clusterNodeList.size()); i++) {
                // 随机抽取一个主题
                int randomIdx = random.nextInt(clusterNodeList.size());
                Map<String, String> randomCluster = clusterNodeList.get(randomIdx);

                String clusterId = (String) randomCluster.get("id");
                String clusterName = (String) randomCluster.get("name"); // 主题名称
                List<Map<String, String>> docNodes = neo4jService.findFileByEntitiesId(List.of(clusterId));


                // 提取该主题下所有文档ID
                List<String> topicDocIds = docNodes.stream()
                        .map(node -> node.get("id"))
                        .collect(Collectors.toList());
                if(topicDocIds.size() <= 1) {
                    continue;
                }
                // 查询完整文档内容
                List<DoFile> topicDocs = fileService.getFilesById(topicDocIds);

                // ======================
                // 按你要求拼接 Prompt
                // ======================
                StringBuilder promptSb = new StringBuilder();
                promptSb.append("主题名称：").append(clusterName).append("\n\n");
                for (int j = 0; j < topicDocs.size(); j++) {
                    promptSb.append("文档").append(j + 1).append("：")
                            .append(topicDocs.get(j).getContent())
                            .append("\n\n");
                }

                // 调用主题级生成模板
                String topicPrompt = String.format(promptConfig.generationSummaryLevel(), promptSb.toString());
                BeanOutputConverter<GeneratedQuestions> testSetConverterGenerated = new BeanOutputConverter<>(GeneratedQuestions.class);
                options = OpenAiChatOptions.builder()
                        .temperature(0.1)
                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, testSetConverterGenerated.getJsonSchema()))
                        .build();

                // 调用大模型
                topicResult = chatClient.prompt()
                        .user(topicPrompt)
                        .options(options)
                        .call()
                        .entity(GeneratedQuestions.class);
                for (GeneratedQuestions.User user : topicResult.users) {
                    for (GeneratedQuestions.Task task : user.tasks) {
                        for (GeneratedQuestions.QAPair qa : task.questions) {
                            TestSet testSet = new TestSet();
                            testSet.setFileId(String.join(",", topicDocIds));
                            testSet.setType(TestSet.Type.SUMMARY_LEVEL);
                            testSet.setQuestion(qa.question);
                            testSet.setAnswer(qa.answer);
                            testSet.setAccording(qa.according);
                            testSets.add(testSet);
                            topicLevelCount++;
                        }
                    }
                }

                log.info("主题级进度：{}% ({}/{})",
                        (i + 1) * 100 / SUMMARY_LEVEL, i + 1, SUMMARY_LEVEL);
                TextUtils.printProgress(i + 1, SUMMARY_LEVEL, "主题级生成进度");
            }
            log.info("主题级问题生成完成，数量：{}", topicLevelCount);
            log.info("生成结果",topicResult);

            // 写文件
             TextUtils.writeJsonl(outputPath, testSets);


        } catch (
                Exception e) {
            e.printStackTrace();
            log.error("生成测试集失败", e.getMessage());
        }


    }

    public static class GeneratedQuestions {
        private List<User> users;

        public List<User> getUsers() { return users; }
        public void setUsers(List<User> users) { this.users = users; }

        public static class User {
            private String description;
            private List<Task> tasks;

            public String getDescription() { return description; }
            public void setDescription(String d) { this.description = d; }
            public List<Task> getTasks() { return tasks; }
            public void setTasks(List<Task> t) { this.tasks = t; }
        }

        public static class Task {
            private String description;
            private List<QAPair> questions;

            public String getDescription() { return description; }
            public void setDescription(String d) { this.description = d; }
            public List<QAPair> getQuestions() { return questions; }
            public void setQuestions(List<QAPair> q) { this.questions = q; }
        }

        public static class QAPair {
            private String fileId;
            private String question;
            private String answer;
            private String according;
            public String getFileId() { return fileId; }
            public void setFileId(String f) { this.fileId = f; }
            public String getQuestion() { return question; }
            public void setQuestion(String q) { this.question = q; }
            public String getAnswer() { return answer; }
            public void setAnswer(String a) { this.answer = a; }
            public String getAccording() { return according; }
            public void setAccording(String a) { this.according = a; }
        }
    }
}
