package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.neo4j.model.Triple;
import com.bistu.hkgrag.llm.model.extraction.Entity;
import com.bistu.hkgrag.llm.model.extraction.EntityResult;
import com.bistu.hkgrag.llm.model.extraction.Relation;
import com.bistu.hkgrag.llm.model.extraction.RelationResult;
import com.bistu.hkgrag.llm.utils.PromptTemplates;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ExtractionService {
    private static Logger log = LoggerFactory.getLogger(ExtractionService.class);
    private final ChatClient chatClient;
    private OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ExtractionService(@Qualifier("qwen7bInsChatModel") @Autowired OpenAiChatModel chatModel,
                             @Autowired ObjectMapper objectMapper,
                             @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
    }

    public void entitiesAndRelationExtractionMultiThread(String inputPath, String outputPath) {
        // 主流程并发控制
        Semaphore mainSemaphore = new Semaphore(8);

        ExecutorService executor = new ThreadPoolExecutor(
                10, 20, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 重试队列
        Queue<DoFile> retryQueue = new ConcurrentLinkedQueue<>();
        //设置写文件的实体和关系队列
        BlockingQueue<List<Triple>> entityQueue = new LinkedBlockingQueue<>();
        BlockingQueue<List<Triple>> relationQueue = new LinkedBlockingQueue<>();

        AtomicBoolean running = new AtomicBoolean(true);

        Thread writerThread = new Thread(() -> {

            List<Triple> entityBuffer = new ArrayList<>();
            List<Triple> relationBuffer = new ArrayList<>();
            int batchSize = 100;

            try {
                while (running.get()
                        || !entityQueue.isEmpty()
                        || !relationQueue.isEmpty()) {

                    try {
                        List<Triple> e = entityQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (e != null) entityBuffer.addAll(e);

                        List<Triple> r = relationQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (r != null) relationBuffer.addAll(r);

                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    // =========================
                    // batch flush
                    // =========================
                    if (entityBuffer.size() >= batchSize) {
                        TextUtils.writeJsonlAppend(
                                outputPath + "/all_entities.jsonl",
                                entityBuffer
                        );
                        entityBuffer.clear();
                    }

                    if (relationBuffer.size() >= batchSize) {
                        TextUtils.writeJsonlAppend(
                                outputPath + "/all_relations.jsonl",
                                relationBuffer
                        );
                        relationBuffer.clear();
                    }
                }

                // =========================
                // 最终 flush
                // =========================
                if (!entityBuffer.isEmpty()) {
                    TextUtils.writeJsonlAppend(outputPath + "/all_entities.jsonl", entityBuffer);
                }

                if (!relationBuffer.isEmpty()) {
                    TextUtils.writeJsonlAppend(outputPath + "/all_relations.jsonl", relationBuffer);
                }

            } catch (Exception e) {
                log.error("写线程异常", e);
            }

        }, "writer-thread");

        writerThread.start();

        try {
            List<DoFile> fileList = TextUtils.readJson(inputPath);
            //if (fileList.size() >= 500) fileList = fileList.subList(0, 10);
            log.info("共需要处理[{}]个文档分块", fileList.size());

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            int total = fileList.size();

            // =========================
            // 🚀 主流程
            // =========================
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DoFile file : fileList) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    boolean success = false;
                    try {
                        mainSemaphore.acquire();
                        try {
                            List<Triple> tempFiles = new ArrayList<>();
                            List<Triple> tempEntities = new ArrayList<>();
                            List<Triple> tempRelations = new ArrayList<>();
                            processSingleFile(file, tempFiles, tempEntities, tempRelations, new HashMap<>());

                            List<Triple> combined = new ArrayList<>();
                            combined.addAll(tempFiles);
                            combined.addAll(tempEntities);

                            // 放入队列，由写线程统一写入
                            entityQueue.offer(combined);
                            relationQueue.offer(tempRelations);

                            success = true;
                        } finally {
                            mainSemaphore.release();
                        }
                    } catch (Exception e) {
                        log.error("首次处理失败，加入重试队列: {}", file.getFileId(), e);
                        retryQueue.add(file);
                    }

                    if (success) successCount.incrementAndGet();
                    int current = processedCount.incrementAndGet();
                    log.info("[主流程] 进度：{}/{} | 成功：{} | 待重试：{}",
                            current, total, successCount.get(), retryQueue.size());
                    log.info("文档[{}]处理完成", file.getChunkId());

                }, executor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("==================================================");
            log.info("✅ 主流程全部完成！成功：{} | 待重试：{}", successCount.get(), retryQueue.size());
            log.info("==================================================");

            executor.shutdown();
            if (!executor.awaitTermination(3, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }

            // =========================
            // 🔁 重试流程
            // =========================
            if (!retryQueue.isEmpty()) {
                int retryTotal = retryQueue.size();
                log.info("🚀 开始重试流程，待重试文档数: {}", retryTotal);

                ExecutorService retryExecutor = new ThreadPoolExecutor(
                        3, 6, 60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(retryTotal * 2),
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );
                //重试信号量
                Semaphore retrySemaphore = new Semaphore(5);
                AtomicInteger retryProcessed = new AtomicInteger(0);
                List<CompletableFuture<Void>> retryFutures = new ArrayList<>();

                for (DoFile file : retryQueue) {
                    retryFutures.add(CompletableFuture.runAsync(() -> {
                        boolean success = false;
                        int maxRetry = 1;
                        for (int attempt = 1; attempt <= maxRetry; attempt++) {
                            try {
                                retrySemaphore.acquire();
                                try {
                                    List<Triple> tempFiles = new ArrayList<>();
                                    List<Triple> tempEntities = new ArrayList<>();
                                    List<Triple> tempRelations = new ArrayList<>();

                                    processSingleFile(file, tempFiles, tempEntities, tempRelations, new HashMap<>());

                                    List<Triple> combined = new ArrayList<>();
                                    combined.addAll(tempFiles);
                                    combined.addAll(tempEntities);

                                    entityQueue.offer(combined);
                                    relationQueue.offer(tempRelations);
                                    success = true;
                                    break;
                                } finally {
                                    retrySemaphore.release();
                                }
                            } catch (Exception e) {
                                log.error("[重试失败] {} 第{}次", file.getFileId(), attempt);
                                if (attempt < maxRetry) {
                                    try {
                                        Thread.sleep(1000L * attempt);
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }
                        }

                        if (!success) {
                            failCount.incrementAndGet();
                            log.error("[❌ 最终失败] fileId:{}", file.getFileId());
                        } else {
                            successCount.incrementAndGet();
                        }

                        int current = retryProcessed.incrementAndGet();
                        log.info("[重试流程] 进度：{}/{} | 总成功：{} | 最终失败：{}",
                                current, retryTotal, successCount.get(), failCount.get());

                    }, retryExecutor));
                }

                CompletableFuture.allOf(retryFutures.toArray(new CompletableFuture[0])).join();
                retryExecutor.shutdown();
                if (!retryExecutor.awaitTermination(3, TimeUnit.MINUTES)) {
                    retryExecutor.shutdownNow();
                }
            }

            // =========================
            // ✅ 安全关闭写线程
            // =========================
            running.set(false);
            writerThread.interrupt();
            writerThread.join(30000); // 最多等30秒确保写完

            // =========================
            // 📊 最终统计
            // =========================
            log.info("\n");
            log.info("========================================================");
            log.info("                  任务全部完成！");
            log.info("========================================================");
            log.info("总文档数：{}", total);
            log.info("最终成功：{}", successCount.get());
            log.info("最终失败：{}", failCount.get());
            log.info("成功率：{}%", String.format("%.2f", successCount.get() * 100.0 / total));
            log.info("========================================================");

        } catch (Exception e) {
            log.error("实体关系抽取任务执行异常", e);
            try {
                writerThread.interrupt();
                writerThread.join(10000);
            } catch (Exception ex) {
                log.error("关闭写线程失败", ex);
            }
        }
    }


    /**
     * 单个文档处理逻辑封装
     */
    /**
     * @param file
     * @param totalFiles
     * @param totalEntities
     * @param totalRelations
     * @param subjectToIdMap
     * @throws Exception
     */
    private void processSingleFile(DoFile file,
                                   Collection<Triple> totalFiles,
                                   Collection<Triple> totalEntities,
                                   Collection<Triple> totalRelations,
                                   Map<String, String> subjectToIdMap) throws Exception {
        // 🚧 使用临时集合，保证原子性
        List<Triple> tempFiles = new ArrayList<>();
        List<Triple> tempEntities = new ArrayList<>();
        List<Triple> tempRelations = new ArrayList<>();

        log.info("正在处理文件[{}]", file.getChunkId());
        Map<String, String> tempSubjectToIdMap = new HashMap<>();

//        // 1️⃣ 提取文档实体
        String file_Node_prompt = String.format(PromptTemplates.File_Node_Extraction, file.getFileName(), file.getContent());
        BeanOutputConverter<EntityResult> fileEntityConverter = new BeanOutputConverter<>(EntityResult.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileEntityConverter.getJsonSchema()))
                .build();

        EntityResult entityResult = chatClient.prompt()
                .user(file_Node_prompt)
                .options(options)
                .call()
                .entity(EntityResult.class);

        for (Entity fileEntity : entityResult.EntityList()) {
            Triple fileTriple = new Triple();
            fileTriple.setId(UUID.randomUUID().toString());
            fileTriple.setSubjectId(file.getFileId());
            fileTriple.setSubject(fileEntity.subject());
            fileTriple.setPredicate(fileEntity.predicate());
            fileTriple.setObject(fileEntity.object());
            fileTriple.setType(Triple.Type.PROPERTY);
            fileTriple.setSubjectType("文档");
            tempFiles.add(fileTriple);
        }

        // 2️⃣ 提取文档内容实体
        String file_Extraction_prompt = String.format(PromptTemplates.Content_Entity_Extraction, file.getContent());
        entityResult = chatClient.prompt().user(file_Extraction_prompt).options(options).call().entity(EntityResult.class);

        List<Triple> contentEntityTriples = new ArrayList<>();
        for (Entity contentEntity : entityResult.EntityList()) {
            Triple contentTriple = new Triple();
            contentTriple.setId(UUID.randomUUID().toString());
            contentTriple.setSubject(contentEntity.subject());
            contentTriple.setPredicate(contentEntity.predicate());
            contentTriple.setObject(contentEntity.object());
            contentTriple.setType(Triple.Type.PROPERTY);
            contentTriple.setSubjectType(contentEntity.subjectType());

            String subject = contentEntity.subject();
            String subjectId = tempSubjectToIdMap.computeIfAbsent(subject, k -> UUID.randomUUID().toString());
            contentTriple.setSubjectId(subjectId);

            contentEntityTriples.add(contentTriple);
            tempEntities.add(contentTriple);
        }

        // 3️⃣ 建立文档-内容实体关系
        Map<String, Object> docIndex = new HashMap<>();
        docIndex.put("fileId", file.getFileId());
        docIndex.put("fileName", file.getFileName());

        List<Map<String, Object>> entityIndexList = new ArrayList<>();
        Map<String, Map<String, Object>> entityIndexMap = new HashMap<>();
        for (Triple t : contentEntityTriples) {
            if (!entityIndexMap.containsKey(t.getSubjectId())) {
                Map<String, Object> e = new HashMap<>();
                e.put("id", t.getSubjectId());
                e.put("name", t.getSubject());
                e.put("type", t.getSubjectType());
                entityIndexMap.put(t.getSubjectId(), e);
            }
        }
        entityIndexList.addAll(entityIndexMap.values());

        String docTriplesJson = objectMapper.writeValueAsString(docIndex);
        String entityTriplesJson = objectMapper.writeValueAsString(entityIndexList);

        // String file_Relation_prompt = String.format(PromptTemplates.File_Relation_Extraction, docTriplesJson, entityTriplesJson);
        BeanOutputConverter<RelationResult> fileAndEntityRelationConverter = new BeanOutputConverter<>(RelationResult.class);
        options = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileAndEntityRelationConverter.getJsonSchema()))
                .build();
//        RelationResult relationResult = chatClient.prompt()
//                .user(file_Relation_prompt)
//                .options(options)
//                .call()
//                .entity(RelationResult.class);
//
//        for (Relation relation : relationResult.relationList()) {
//            Triple fileAndEntityRelationTriple = new Triple();
//            fileAndEntityRelationTriple.setId(UUID.randomUUID().toString());
//            fileAndEntityRelationTriple.setSubjectId(relation.subjectId());
//            fileAndEntityRelationTriple.setSubject(relation.subject());
//            fileAndEntityRelationTriple.setSubjectType(relation.subjectType());
//            fileAndEntityRelationTriple.setPredicate(relation.predicate());
//            fileAndEntityRelationTriple.setType(Triple.Type.RELATION);
//            fileAndEntityRelationTriple.setObjectId(relation.objectId());
//            fileAndEntityRelationTriple.setObject(relation.object());
//            fileAndEntityRelationTriple.setObjectType(relation.objectType());
//            tempRelations.add(fileAndEntityRelationTriple);
//        }
        //保证每个内容节点都与文档内容相连
//        Set<String> connectedEntityIds = relationResult.relationList()
//                .stream()
//                .map(Relation::objectId) // 文档 -> 实体
//                .collect(Collectors.toSet());

        // 遍历所有实体，补全缺失关系
        for (Map<String, Object> entity : entityIndexList) {
            String entityId = (String) entity.get("id");
            Triple fallbackRelation = new Triple();
            fallbackRelation.setId(UUID.randomUUID().toString());
            fallbackRelation.setSubjectId(file.getFileId());
            fallbackRelation.setSubject(file.getFileName());
            fallbackRelation.setSubjectType("文档");
            fallbackRelation.setPredicate("包含"); // 或 “提及”、“涉及”
            fallbackRelation.setObjectId(entityId);
            fallbackRelation.setObject((String) entity.get("name"));
            fallbackRelation.setObjectType((String) entity.get("type"));
            fallbackRelation.setType(Triple.Type.RELATION);
            totalRelations.add(fallbackRelation);

        }

        // 4️⃣ 抽取实体之间关系
        String entities_Relation_prompt = String.format(PromptTemplates.Entities_Relation_Extraction, file.getContent(), entityTriplesJson);
        RelationResult relationResult = chatClient.prompt()
                .user(entities_Relation_prompt)
                .options(options)
                .call()
                .entity(RelationResult.class);
        for (Relation relation : relationResult.relationList()) {
            Triple contentRelationTriple = new Triple();
            contentRelationTriple.setId(UUID.randomUUID().toString());
            contentRelationTriple.setSubjectId(relation.subjectId());
            contentRelationTriple.setSubject(relation.subject());
            contentRelationTriple.setSubjectType(relation.subjectType());
            contentRelationTriple.setPredicate(relation.predicate());
            contentRelationTriple.setType(Triple.Type.RELATION);
            contentRelationTriple.setObjectId(relation.objectId());
            contentRelationTriple.setObject(relation.object());
            contentRelationTriple.setObjectType(relation.objectType());
            tempRelations.add(contentRelationTriple);
        }

        // ✅ 全部成功，合并到总集合
        totalFiles.addAll(tempFiles);
        totalEntities.addAll(tempEntities);
        totalRelations.addAll(tempRelations);

        // ✅ 合并 subjectId 映射
        subjectToIdMap.putAll(tempSubjectToIdMap);

    }

//    /**
//     * 使用LLM从指定源路径json文件中抽取信息输出实体、关系的json文件到指定文件下
//     * 1.抽取文档实体及属性
//     * 2.抽取文档内容实体及属性
//     * 3.建立文档内容实体->文档实体之间的关系
//     * 4.抽取出内容实体之间的关系
//     *
//     * @param inputPath  待抽取文件路径
//     * @param outputPath 实体、关系json文件输出路径
//     */
//    public void entitiesAndRelationExtraction(String inputPath, String outputPath) {
//
//        if (inputPath == null) {
//            log.error("源文件路径[{}]不存在", inputPath);
//        }
//        if (outputPath == null) {
//            log.error("输出文件路径[{}]不存在", outputPath);
//        }
//        try {
//            //TODO 测试先获取前10个chunk
//            List<DoFile> fileList = TextUtils.readJson(inputPath).subList(0, 10);
//            // List<DoFile> fileList = TextUtils.readJson(inputPath);
//            log.info("共需要处理[{}]个文档分块", fileList.size());
//            //存放文档Triple
//            List<Triple> totalFiles = new ArrayList<>();
//            //存放文档内容实体Triple
//            List<Triple> totalEntities = new ArrayList<>();
//            //存放关系
//            List<Triple> totalRelations = new ArrayList<>();
//            Map<String, String> subjectToIdMap = new HashMap<>();
//
//            for (int i = 0; i < fileList.size(); i++) {
//                DoFile file = fileList.get(i);
//                log.info("开始处理第 {} 个文档，文档ID：{}，名称：{}", i + 1, file.getFileId(), file.getFileName());
//                /**
//                 * 提取文档实体及实体属性
//                 * 1.获取chunk
//                 * 2.填充提示词
//                 * 3.调用大模型填充json模板
//                 * 4.转为Triples存储
//                 */
//                String file_Node_prompt = String.format(PromptTemplates.File_Node_Extraction, file.getFileName(), file.getContent());
//                //让大模型按照指定的 JSON Schema 输出结构化数据，并自动解析成 Java 对象 EntityResult
//                //定义一个输出解析器，把LLM的回答转换成Java Bean
////                BeanOutputConverter<EntityResult> fileEntityConverter =
////                        new BeanOutputConverter<>(EntityResult.class);
////
////                Prompt prompt = new Prompt(
////                        file_Node_prompt,
////                        OpenAiChatOptions.builder()
////                                .temperature(0.1)
////                                .responseFormat(
////                                        new ResponseFormat(
////                                                ResponseFormat.Type.JSON_SCHEMA,
////                                                fileEntityConverter.getJsonSchema()
////                                        )
////                                )
////                                .build()
////                );
////                ChatResponse response = chatModel.call(prompt);
////                String content = response.getResult().getOutput().getText();
////                EntityResult entityResult = objectMapper.readValue(content, EntityResult.class);
//                BeanOutputConverter<EntityResult> fileEntityConverter = new BeanOutputConverter<>(EntityResult.class);
////                OpenAiChatOptions options = OpenAiChatOptions.builder()
////                        .temperature(0.1)
////                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileEntityConverter.getJsonSchema()))
////                        .build();
//                OpenAiChatOptions options = OpenAiChatOptions.builder()
//                        .temperature(0.1) // 保持低温度
//                        // 正确的 JSON_OBJECT 构建方式
//                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileEntityConverter.getJsonSchema()))
//                        .build();
//                EntityResult entityResult = chatClient.prompt().user(file_Node_prompt).options(options).call().entity(EntityResult.class);
//                //将record映射到数据库层面的dao(Entity) 并补充文件的subjectId
//                for (Entity fileEntity : entityResult.EntityList()) {
//                    Triple fileTriple = new Triple();
//                    fileTriple.setId(UUID.randomUUID().toString());
//                    fileTriple.setSubjectId(file.getFileId());
//                    fileTriple.setSubject(fileEntity.subject());
//                    fileTriple.setPredicate(fileEntity.predicate());
//                    fileTriple.setObject(fileEntity.object());
//                    fileTriple.setType(Triple.Type.PROPERTY);
//                    fileTriple.setSubjectType("文档");
//                    totalFiles.add(fileTriple);
//                }
//
//                /**
//                 * 提取文档内容中的实体及实体属性
//                 */
//                String file_Extraction_prompt = String.format(PromptTemplates.Content_Entity_Extraction, file.getContent());
//                entityResult = chatClient.prompt().user(file_Extraction_prompt).options(options).call().entity(EntityResult.class);
//                List<Triple> contentEntityTriples = new ArrayList<>();
//                for (Entity contentEntity : entityResult.EntityList()) {
//                    Triple contentTriple = new Triple();
//                    //为每个抽取出的属性添加id
//                    contentTriple.setId(UUID.randomUUID().toString());
//                    contentTriple.setSubject(contentEntity.subject());
//                    contentTriple.setPredicate(contentEntity.predicate());
//                    contentTriple.setObject(contentEntity.object());
//                    contentTriple.setType(Triple.Type.PROPERTY);
//                    contentTriple.setSubjectType(contentEntity.subjectType());
//                    //对主体id去重，存在则复用Id，不存在则生成新Id并存入映射
//                    String subject = contentEntity.subject();
//                    String subjectId = subjectToIdMap.computeIfAbsent(
//                            subject,
//                            k -> UUID.randomUUID().toString()
//                    );
//                    contentTriple.setSubjectId(subjectId);
//                    //放入临时三元组，在抽取内容实体之间的关系时使用
//                    contentEntityTriples.add(contentTriple);
//                    //加入最终的三元组列表作为结果
//                    totalEntities.add(contentTriple);
//                }
//                /**
//                 * 建立文档实体-文档内容实体之间的关系
//                 */
//                //第二步抽取的实体带有属性，存在一个实体多个属性的情况，故需要对实体去重
//                Map<String, Object> docIndex = new HashMap<>();
//                docIndex.put("fileId", file.getFileId());
//                docIndex.put("fileName", file.getFileName());
//                List<Map<String, Object>> entityIndexList = new ArrayList<>();
//                //用subjectId去重
//                Map<String, Map<String, Object>> entityIndexMap = new HashMap<>();
//
//                for (Triple t : contentEntityTriples) {
//                    //判断当前内容实体的id是否存在
//                    if (!entityIndexMap.containsKey(t.getSubjectId())) {
//                        Map<String, Object> e = new HashMap<>();
//                        e.put("id", t.getSubjectId());
//                        e.put("name", t.getSubject());
//                        e.put("type", t.getSubjectType());
//                        entityIndexMap.put(t.getSubjectId(), e);
//                    }
//                }
//                //存储去重后的jsonl实体信息
//                entityIndexList.addAll(entityIndexMap.values());
//                String docTriplesJson = objectMapper.writeValueAsString(docIndex);
//                String entityTriplesJson = objectMapper.writeValueAsString(entityIndexList);
//                //通过模型+提示词抽取出 第i个文档与该文档所有内容实体之间的关系
//
//                String file_Relation_prompt = String.format(PromptTemplates.File_Relation_Extraction, docTriplesJson, entityTriplesJson);
//                BeanOutputConverter<RelationResult> fileAndEntityRelationConverter = new BeanOutputConverter<>(RelationResult.class);
//                options = OpenAiChatOptions.builder()
//                        .temperature(0.1)
//                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileAndEntityRelationConverter.getJsonSchema()))
//                        .build();
//                RelationResult relationResult = chatClient.prompt()
//                        .user(file_Relation_prompt)
//                        .options(options)
//                        .call()
//                        .entity(RelationResult.class);
//                //record到triple映射并给关系生成唯一id
//                for (Relation relation : relationResult.relationList()) {
//                    Triple fileAndEntityRelationTriple = new Triple();
//                    fileAndEntityRelationTriple.setId(UUID.randomUUID().toString());
//                    fileAndEntityRelationTriple.setSubjectId(relation.subjectId());
//                    fileAndEntityRelationTriple.setSubject(relation.subject());
//                    fileAndEntityRelationTriple.setSubjectType(relation.subjectType());
//                    fileAndEntityRelationTriple.setPredicate(relation.predicate());
//                    fileAndEntityRelationTriple.setType(Triple.Type.RELATION);
//                    fileAndEntityRelationTriple.setObjectId(relation.objectId());
//                    fileAndEntityRelationTriple.setObject(relation.object());
//                    fileAndEntityRelationTriple.setObjectType(relation.objectType());
//                    totalRelations.add(fileAndEntityRelationTriple);

//                }
//                //保证每个内容节点都与文档内容相连
//                Set<String> connectedEntityIds = relationResult.relationList()
//                        .stream()
//                        .map(Relation::objectId) // 文档 -> 实体
//                        .collect(Collectors.toSet());
//
//                // 遍历所有实体，补全缺失关系
//                for (Map<String, Object> entity : entityIndexList) {
//                    String entityId = (String) entity.get("id");
//                    if (!connectedEntityIds.contains(entityId)) {
//                        // 创建兜底关系：文档 -> 实体
//                        Triple fallbackRelation = new Triple();
//                        fallbackRelation.setId(UUID.randomUUID().toString());
//                        fallbackRelation.setSubjectId(file.getFileId());
//                        fallbackRelation.setSubject(file.getFileName());
//                        fallbackRelation.setSubjectType("文档");
//                        fallbackRelation.setPredicate("包含"); // 或 “提及”、“涉及”
//                        fallbackRelation.setObjectId(entityId);
//                        fallbackRelation.setObject((String) entity.get("name"));
//                        fallbackRelation.setObjectType((String) entity.get("type"));
//                        fallbackRelation.setType(Triple.Type.RELATION);
//                        totalRelations.add(fallbackRelation);
//                    }
//                }
//                /**
//                 * 抽取出内容实体之间的关系
//                 */
//                String entities_Relation_prompt = String.format(PromptTemplates.Entities_Relation_Extraction, file.getContent(), entityTriplesJson);
//                relationResult = chatClient.prompt()
//                        .user(entities_Relation_prompt)
//                        .options(options)
//                        .call()
//                        .entity(RelationResult.class);
//                for (Relation relation : relationResult.relationList()) {
//                    Triple contentRelationTriple = new Triple();
//                    contentRelationTriple.setId(UUID.randomUUID().toString());
//                    contentRelationTriple.setSubjectId(relation.subjectId());
//                    contentRelationTriple.setSubject(relation.subject());
//                    contentRelationTriple.setSubjectType(relation.subjectType());
//                    contentRelationTriple.setPredicate(relation.predicate());
//                    contentRelationTriple.setType(Triple.Type.RELATION);
//                    contentRelationTriple.setObjectId(relation.objectId());
//                    contentRelationTriple.setObject(relation.object());
//                    contentRelationTriple.setObjectType(relation.objectType());
//                    totalRelations.add(contentRelationTriple);
//                }
//                log.info("---------- 第 " + (i + 1) + " 个文档处理完成 ----------");
//            }
//            // 输出实体 JSONL
////            TextUtils.writeJsonl(outputPath + "/fileEntities.jsonl", totalFiles);
////            TextUtils.writeJsonl(outputPath + "/contentEntities.jsonl", totalEntities);
//            // 合并两个列表（保持原有顺序、原有结构）
//            List<Object> combined = new ArrayList<>();
//            combined.addAll(totalFiles);
//            combined.addAll(totalEntities);
//            // 写入同一个文件
//            TextUtils.writeJsonl(outputPath + "/all_entities.jsonl", combined);
//
//            // 输出关系 JSONL
//            TextUtils.writeJsonl(outputPath + "/all_relations.jsonl", totalRelations);
//            log.info("抽取信息成功");
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("抽取信息失败", e);
//        }
//    }

    /**
     * 使用大模型总结文档主题
     *
     * @param inputPath  预处理后的文档
     * @param outputPath 最终文档
     */
    public void fileTopicExtraction(String inputPath, String outputPath) {
        //chunk总数
        AtomicInteger totalChunks = new AtomicInteger(0);
        //成功chunk数
        AtomicInteger successChunks = new AtomicInteger(0);
        //失败chunk数
        AtomicInteger failChunks = new AtomicInteger(0);

        // 记录失败的 chunkId
        ConcurrentLinkedQueue<String> failedChunkIds = new ConcurrentLinkedQueue<>();

        ExecutorService executor = new ThreadPoolExecutor(
                8,
                10,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // ✅ 限流（防止平台限流）
        Semaphore semaphore = new Semaphore(5);

        try {
            log.info("开始提取文档主题");

            // ✅ 1. 读取数据
            List<DoFile> fileList = TextUtils.readJson(inputPath);

            // ✅ 2. 按 fileId 分组（文档级）
            Map<String, List<DoFile>> docChunksMap = fileList.stream()
                    .collect(Collectors.groupingBy(DoFile::getFileId));
            // 总文档数
            int totalDocs = docChunksMap.size();
            // 已完成
            AtomicInteger completedDocs = new AtomicInteger(0);

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // ✅ 3. 文档级并发处理
            for (Map.Entry<String, List<DoFile>> entry : docChunksMap.entrySet()) {

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {

                    String fileId = entry.getKey();
                    List<DoFile> chunks = entry.getValue();
                    List<String> chunkTopics = new ArrayList<>();

                    try {
                        // ========= Step1：chunk主题抽取 =========
                        for (DoFile chunk : chunks) {
                            totalChunks.incrementAndGet();
                            semaphore.acquire();
                            try {
                                String promptStr = String.format(
                                        PromptTemplates.File_Topic_Extraction,
                                        chunk.getFileName(),
                                        chunk.getContent()
                                );
                                chatClient.prompt(promptStr);

//                                ChatResponse response = chatModel.call(new Prompt(promptStr));
//                                String topic = response.getResult().getOutput().getText().trim();
                                // 1. 构建请求并获取完整响应
                                ChatResponse response = chatClient.prompt(promptStr)
                                        .call()
                                        .chatResponse();
                                String topic = response.getResult().getOutput().getText().trim();

                                if (topic != null && !topic.isEmpty()) {
                                    chunk.setTopic(topic);
                                    chunkTopics.add(topic);
                                    successChunks.incrementAndGet();
                                }else{
                                    failChunks.incrementAndGet();
                                    failedChunkIds.add(chunk.getChunkId());
                                }

                            } finally {
                                semaphore.release();
                            }
                        }

                        // ========= Step2：文档级主题汇总 =========
                        String finalTopic;

                        if (chunkTopics.isEmpty()) {
                            finalTopic = "";
                        } else if (chunkTopics.size() == 1) {
                            finalTopic = chunkTopics.get(0);
                        } else {
                            semaphore.acquire();
                            try {
                                String summaryPrompt = String.format(
                                        PromptTemplates.File_Topic_Summary_Extraction,
                                        chunks.get(0).getFileName(),
                                        String.join("\n", chunkTopics)
                                );

//                                ChatResponse finalResponse = chatModel.call(new Prompt(summaryPrompt));
//                                finalTopic = finalResponse.getResult().getOutput().getText().trim();
                                ChatResponse response = chatClient.prompt(summaryPrompt)
                                        .call()
                                        .chatResponse();
                                finalTopic = response.getResult().getOutput().getText().trim();

                            } finally {
                                semaphore.release();
                            }
                        }

                        // ========= Step3：统一设置文档主题 =========
                        for (DoFile chunk : chunks) {
                            chunk.setTopic(finalTopic);
                        }


                        int done = completedDocs.incrementAndGet();
                        double rate = (done * 100.0) / totalDocs;
                        log.info("[进度] {}/{} | 完成率: {}% | 文档[{}] | 主题: {}",
                                done, totalDocs,
                                String.format("%.2f", rate),
                                fileId, finalTopic);


                    } catch (Exception e) {
                        log.error("文档处理失败 fileId={}", fileId, e);
                    }

                }, executor);

                futures.add(future);
            }

            // ✅ 等待全部任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            // 直接从 docChunksMap 重新拼装（确保是被修改后的对象）
            List<DoFile> result = docChunksMap.values()
                    .stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            // ✅ 写文件
            TextUtils.writeJsonl(outputPath, result);
            log.info("========== 处理统计 ==========");
            log.info("总 chunk 数: {}", totalChunks.get());
            log.info("成功 chunk 数: {}", successChunks.get());
            log.info("失败 chunk 数: {}", failChunks.get());
            log.info("失败 chunkId 列表: {}", failedChunkIds);

        } catch (Exception e) {
            log.error("提取文档主题错误", e);
        } finally {
            executor.shutdown();
        }
    }
//    public void fileTopicExtraction(String inputPath, String outputPath) {
//        if (inputPath == null) {
//            log.error("源文件路径[{}]不存在", inputPath);
//        }
//        if (outputPath == null) {
//            log.error("输出文件路径[{}]不存在", outputPath);
//        }
//        try {
//            /**
//             * 提取文档主题
//             */
//            log.info("开始提取文档主题");
//            List<DoFile> fileList = TextUtils.readJson(inputPath);
//
//
//            // 1. 按 fileId 聚合所有分块
//            Map<String, List<DoFile>> docChunksMap = fileList.stream()
//                    .collect(Collectors.groupingBy(file -> file.getFileId()));
//            int totalDocs = docChunksMap.size();
//            int current = 0;
//            for (Map.Entry<String, List<DoFile>> entry : docChunksMap.entrySet()) {
//                String fileId = entry.getKey();
//                List<DoFile> chunks = entry.getValue();
//                List<String> chunkTopics = new ArrayList<>();
//
//                // 2. 每个分块抽取主题
//                for (DoFile chunk : chunks) {
//                    String file_topic_prompt = String.format(PromptTemplates.File_Topic_Extraction, chunk.getFileName(), chunk.getContent());
//                    Prompt prompt = new Prompt(file_topic_prompt);
//                    ChatResponse response = chatModel.call(prompt);
//                    String topic = response.getResult().getOutput().getText().trim();
//                    if (!topic.isEmpty()) {
//                        chunk.setTopic(topic); // 可选：记录分块主题
//                        chunkTopics.add(topic);
//                    }
//                }
//                // 设置文档最终主题
//                String finalTopic;
//                if (chunkTopics.size() == 1) {
//                    // 只有一个分块，直接使用该主题
//                    finalTopic = chunkTopics.get(0);
//                } else {
//                    // 多个分块，汇总生成最终主题
//                    String file_Topic_Summary_Extraction = String.format(PromptTemplates.File_Topic_Summary_Extraction, chunks.get(0).getFileName(), String.join("\n", chunkTopics));
//                    Prompt finalPrompt = new Prompt(file_Topic_Summary_Extraction);
//                    ChatResponse finalResponse = chatModel.call(finalPrompt);
//                    finalTopic = finalResponse.getResult().getOutput().getText().trim();
//                }
//
//                // 4. 设置每个分块的主题一致，文档级主题统一
//                for (DoFile chunk : chunks) {
//                    chunk.setTopic(finalTopic);
//                }
//
//                current++;
//                double rate = (current * 100.0) / totalDocs;
//                log.info("[进度] {}/{} | 完成率: {}% | 文档[{}]主题: {}",
//                        current, totalDocs,
//                        String.format("%.2f", rate),
//                        fileId, finalTopic);
//            }
//            TextUtils.writeJsonl(outputPath, fileList);
//            log.info("提取文档主题已完成");
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("提取文档主题错误", e);
//        }
//    }


}
