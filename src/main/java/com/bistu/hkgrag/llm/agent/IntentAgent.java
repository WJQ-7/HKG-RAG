package com.bistu.hkgrag.llm.agent;


import com.bistu.hkgrag.config.PromptConfig;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class IntentAgent {
    private static Logger log = LoggerFactory.getLogger(IntentAgent.class);
    private ChatClient chatClient;
    private final RetrievalTools retrievalTools;
    private final ToolCallAdvisor toolCallAdvisor;

    private final ToolCallingManager toolCallingManager;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;
    private ChatMemory chatMemory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final EvidenceStore evidenceStore;
    private final PromptConfig promptConfig;

    /**
     * IntentAgent 内部独立的局部记忆，限制窗口大小。
     * 每次 generationAnswer() 调用前清空，保证单次进入的记忆独立且不会撑爆 token。
     * 只保留最近的20条消息
     */
    private final ChatMemory localMemory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();
    private final MessageChatMemoryAdvisor localMemoryAdvisor =
            MessageChatMemoryAdvisor.builder(localMemory).build();

    /**
     * 答案生成阶段检索内容最大字符数。
     * 模型上下文限制 32768 tokens，中文约 2 字/token。
     * 保留约 10000 tokens 给 prompt 模板和 LLM 回答，检索内容上限约 22768 tokens ≈ 45000 字符。
     * 取保守值 20000 字符，确保不会超限。
     */
    private static final int MAX_RETRIEVED_CHARS = 20000;




    public IntentAgent(
            @Qualifier("qwen7bInsChatClient") @Autowired ChatClient chatClient,
            @Autowired RetrievalTools retrievalTools,
            @Autowired ToolCallAdvisor toolCallAdvisor,
            @Autowired ToolCallingManager toolCallingManager,
            @Autowired MessageChatMemoryAdvisor chatMemoryAdvisor,
            @Autowired ChatMemory chatMemory,
            @Autowired EvidenceStore evidenceStore,
            @Autowired PromptConfig promptConfig

    ) {

        this.chatClient = chatClient;
        this.retrievalTools = retrievalTools;
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolCallingManager = toolCallingManager;
        this.chatMemoryAdvisor = chatMemoryAdvisor;
        this.chatMemory = chatMemory;
        this.evidenceStore = evidenceStore;
        this.promptConfig = promptConfig;
    }

    @Schema(description = "工具调用轨迹记录")
    public record ToolCallTrace(

            @JsonProperty(required = true)
            @Schema(description = "工具名称")
            String toolName,

            @JsonProperty(required = true)
            @Schema(description = "工具输入参数")
            String input,

            @JsonProperty(required = true)
            @Schema(description = "证据ID")
            String evidenceId,

            @JsonProperty(required = true)
            @Schema(description = "工具返回结果摘要")
            String summary

    ) {}

    @Schema(description = "回答结果")
    public record AnswerResult(
            @JsonProperty(required = true)
            @Schema(description = "用户问题")
            String question,

            @JsonProperty(required = true)
            @Schema(description = "最终答案")
            String finalAnswer,

            @JsonProperty(required = true)
            @Schema(description = "工具调用轨迹")
            List<ToolCallTrace> toolCalls
    ) {}

    public AnswerResult generationAnswer(String question,QuestionAnalysisAgent.RetrievalPlanResponse plan, String conversationId) {

        // 清空局部记忆，保证每次进入 IntentAgent 记忆干净
        localMemory.clear(conversationId);

        // ========== Step 1: 工具调用阶段 ==========
        // 用复杂的 SystemCOT + RetrievalPrompt 引导 LLM 选择并调用检索工具
        String retrievalUserPrompt = promptConfig.retrievalPrompt().formatted(
                question,
                plan.queryType(),
                plan.keywords(),
                plan.topics()
        );
        String systemPrompt = promptConfig.systemCOT();

        try {
            chatClient.prompt()
                    .system(systemPrompt)
                    .user(retrievalUserPrompt)
                    .tools(retrievalTools)
                    .advisors(advisorSpec -> advisorSpec.param(
                            ChatMemory.CONVERSATION_ID,
                            conversationId
                    ))
                    .advisors(localMemoryAdvisor, toolCallAdvisor)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.warn("工具调用阶段异常: {}。将使用已收集的证据继续生成答案。", e.getMessage());
        }

        // 获取工具调用记录（从局部记忆中提取）
        List<ToolCallTrace> toolCallTraces =
                extractToolTracesFromLocal(conversationId);

        // ========== Step 2: 答案生成阶段 ==========
        String retrievedContent = extractRetrievedContentFromLocal(conversationId);

        String answerUserPrompt = promptConfig.intentAnswerPrompt().formatted(
                question,
                retrievedContent
        );

        String finalAnswer = chatClient.prompt()
                .user(answerUserPrompt)
                .call()
                .content();

        // 结束后清空局部记忆，释放内存
        localMemory.clear(conversationId);

        return new AnswerResult(
                question,
                finalAnswer,
                toolCallTraces
        );

    }

    /**
     * 消融实验专用方法
     * 不依赖问题分析智能体（QuestionAnalysisAgent）的检索规划，
     * 由 LLM 调用检索工具，再生成答案。
     * 无多轮反思循环、无评估智能体；提示词与完整版保持一致
     * （systemCOT + autonomousRetrievalPrompt + intentAnswerPrompt）。
     *
     * @param question       用户问题
     * @param conversationId 会话ID
     * @return 回答结果（含工具调用轨迹）
     */
    public AnswerResult generationAnswerAutonomous(String question, String conversationId) {

        // 清空局部记忆，保证每次进入 IntentAgent 记忆干净
        localMemory.clear(conversationId);

        // ========== Step 1: 自主问题分析 + 工具调用阶段 ==========
        // SystemCOT 与完整版一致；User prompt 要求 LLM 先自主分析问题类型/关键词/主题，再调用工具
        String retrievalUserPrompt = promptConfig.autonomousRetrievalPrompt().formatted(question);
        String systemPrompt = promptConfig.systemCOT();

        try {
            chatClient.prompt()
                    .system(systemPrompt)
                    .user(retrievalUserPrompt)
                    .tools(retrievalTools)
                    .advisors(advisorSpec -> advisorSpec.param(
                            ChatMemory.CONVERSATION_ID,
                            conversationId
                    ))
                    .advisors(localMemoryAdvisor, toolCallAdvisor)
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            log.warn("工具调用阶段异常: {}。将使用已收集的证据继续生成答案。", e.getMessage());
        }

        // 获取工具调用记录（从局部记忆中提取）
        List<ToolCallTrace> toolCallTraces =
                extractToolTracesFromLocal(conversationId);

        // ========== Step 2: 答案生成阶段（与完整版逻辑一致） ==========
        // 从局部记忆中提取检索到的文档内容
        String retrievedContent = extractRetrievedContentFromLocal(conversationId);

        String answerUserPrompt = promptConfig.intentAnswerPrompt().formatted(
                question,
                retrievedContent
        );

        String finalAnswer = chatClient.prompt()
                .user(answerUserPrompt)
                .call()
                .content();

        // 结束后清空局部记忆，释放内存
        localMemory.clear(conversationId);

        return new AnswerResult(
                question,
                finalAnswer,
                toolCallTraces
        );
    }

    /**
     * 从局部记忆中提取工具返回的检索内容，通过 evidenceId 从 EvidenceStore 获取完整内容，
     * 多个工具返回相同文档时会按 (fileId + chunkId) 自动去重。
     */
    private String extractRetrievedContentFromLocal(String conversationId) {
        List<Message> messages = localMemory.get(conversationId);

        // 收集所有工具返回的 DoFile，按 (fileId + chunkId) 去重并保持插入顺序
        java.util.LinkedHashMap<String, DoFile> allFilesMap = new java.util.LinkedHashMap<>();
        boolean hasNonFileContent = false;
        StringBuilder fallbackContent = new StringBuilder();

        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                try {
                    String responseData = response.responseData();
                    if (!StringUtils.hasText(responseData)
                            || "null".equalsIgnoreCase(responseData)) {
                        continue;
                    }
                    RetrievalTools.RetrievalResult result =
                            mapper.readValue(responseData, RetrievalTools.RetrievalResult.class);
                    if (result == null || !StringUtils.hasText(result.evidentId())) {
                        continue;
                    }

                    String fullData = evidenceStore.get(result.evidentId());
                    if (!StringUtils.hasText(fullData)) {
                        if (StringUtils.hasText(result.content())) {
                            fallbackContent.append(result.content()).append("\n\n");
                        }
                        continue;
                    }

                    // 尝试解析并收集所有 DoFile，按 (fileId + chunkId) 去重
                    List<DoFile> files = extractDoFilesFromEvidence(fullData);
                    if (files != null && !files.isEmpty()) {
                        for (DoFile f : files) {
                            String key = (f.getFileId() != null ? f.getFileId() : "")
                                    + "|" + (f.getChunkId() != null ? f.getChunkId() : "");
                            allFilesMap.putIfAbsent(key, f);
                        }
                    } else {
                        // 非文档类型内容（如 searchEntityByName），直接拼接
                        hasNonFileContent = true;
                        fallbackContent.append(result.content()).append("\n\n");
                    }
                } catch (JsonProcessingException e) {
                    log.error("解析工具返回内容失败: {}", response.responseData(), e);
                }
            }
        }

        StringBuilder contentBuilder = new StringBuilder();
        if (!allFilesMap.isEmpty()) {
            int idx = 1;
            for (DoFile doFile : allFilesMap.values()) {
                String docEntry = "相关文档" + idx + "：\n"
                        + "文档名：" + doFile.getFileName() + "\n"
                        + "文档内容：" + doFile.getContent() + "\n\n";
                if (contentBuilder.length() + docEntry.length() > MAX_RETRIEVED_CHARS) {
//                    log.warn("检索内容达到上限{}chars（已拼{}篇文档），截断剩余{}篇防止超出32768 token上下文",
//                            MAX_RETRIEVED_CHARS, idx - 1, allFilesMap.size() - idx + 1);
                    contentBuilder.append("\n...[检索内容达到上下文限制，")
                            .append(allFilesMap.size() - idx + 1)
                            .append("篇文档已截断]...\n");
                    break;
                }
                contentBuilder.append(docEntry);
                idx++;
            }
        }
        if (hasNonFileContent && contentBuilder.length() < MAX_RETRIEVED_CHARS) {
            int remaining = MAX_RETRIEVED_CHARS - contentBuilder.length();
            String fb = fallbackContent.toString();
            if (fb.length() > remaining) {
                fb = fb.substring(0, remaining) + "\n...[其他内容过长已截断]...";
            }
            contentBuilder.append(fb);
        }

        String content = contentBuilder.toString().trim();
        if (StringUtils.hasText(content)) {
            return content;
        }

        // 兜底：从 AssistantMessage 的最终文本中提取
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.getToolCalls() != null
                    && !assistantMessage.getToolCalls().isEmpty()) {
                continue;
            }
            if (message instanceof AssistantMessage assistantMessage) {
                String text = assistantMessage.getText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }

        return "No relevant documents found.";
    }

    /**
     * 从 EvidenceStore 中的 JSON 数据提取 DoFile 列表。
     * 支持 searchFileByQuery（List<DoFile>）和 searchFileByTopic（Map{"clusters":..., "files":...}）两种格式。
     *
     * @return DoFile 列表，解析失败或非文档类型返回 null
     */
    private List<DoFile> extractDoFilesFromEvidence(String fullData) {
        // 尝试按 List<DoFile> 解析（searchFileByQuery / searchEntityByName 等）
        try {
            List<DoFile> fileList = mapper.readValue(fullData, new TypeReference<List<DoFile>>() {});
            if (fileList != null && !fileList.isEmpty()) {
                return fileList;
            }
        } catch (Exception e) {
            // 不是 List 格式，尝试下一种
        }

        // 尝试按 Map{"clusters":..., "files":...} 解析（searchFileByTopic）
        try {
            Map<String, Object> combinedResult = mapper.readValue(
                    fullData, new TypeReference<Map<String, Object>>() {});
            if (combinedResult != null && combinedResult.containsKey("files")) {
                Object filesObj = combinedResult.get("files");
                String filesJson = mapper.writeValueAsString(filesObj);
                List<DoFile> fileList = mapper.readValue(filesJson, new TypeReference<List<DoFile>>() {});
                if (fileList != null && !fileList.isEmpty()) {
                    return fileList;
                }
            }
        } catch (Exception e) {
            // 也不匹配
        }

        return null;
    }

    /**
     * 从局部记忆中提取调用工具的记录
     * @param conversationId 记忆的id
     * @return 自定义工具记录
     */
    private List<ToolCallTrace> extractToolTracesFromLocal(String conversationId) {
        //1.根据对话id从局部记忆中获取完整的消息列表
        List<Message> messages = localMemory.get(conversationId);
        List<ToolCallTrace> toolCallTraces = new ArrayList<>();
        for(int i=0; i<messages.size(); i++) {
            Message message = messages.get(i);
            //跳过非AssistantMessage
            if(!(message instanceof AssistantMessage assistantMessage)) {
                continue;
            }
            //判断是否调用了工具 没有调用直接跳过
            if(assistantMessage.getToolCalls()==null || assistantMessage.getToolCalls().isEmpty()) {
                continue;
            }


            for(AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                String evidenceId = "";
                String summary = "";
                //检查当前消息的下一条消息是不是工具响应消息
                //如果是就把工具返回结果取出
                if(i+1<messages.size() && messages.get(i+1) instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        try {

                            String responseData = response.responseData();

                            if (!StringUtils.hasText(responseData)
                                    || "null".equalsIgnoreCase(responseData)) {

                                log.warn("工具返回为空: {}", toolCall.name());
                                continue;
                            }

                            RetrievalTools.RetrievalResult result =
                                    mapper.readValue(
                                            responseData,
                                            RetrievalTools.RetrievalResult.class
                                    );

                            if (result == null) {
                                log.warn("工具返回结果解析后为空: {}", responseData);
                                continue;
                            }
                            evidenceId = result.evidentId();
                            summary = result.summary();

                        } catch (JsonProcessingException e) {
                            log.error("解析工具返回结果失败: {}",response.responseData(),e);
                        }

                    }
                }
                toolCallTraces.add(new ToolCallTrace(
                        toolCall.name(),
                        toolCall.arguments(),
                        evidenceId,
                        summary
                        ));
            }
        }
        return toolCallTraces;
    }

}
