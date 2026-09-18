package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.config.PromptConfig;
import com.bistu.hkgrag.dao.milvus.model.DoFile;
import com.bistu.hkgrag.dao.neo4j.model.Entity;
import com.bistu.hkgrag.dao.neo4j.model.Relation;
import com.bistu.hkgrag.llm.model.extraction.EntityResult;
import com.bistu.hkgrag.llm.model.extraction.RelationResult;
import com.bistu.hkgrag.preprocess.utils.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ExtractionService {
    private static Logger log = LoggerFactory.getLogger(ExtractionService.class);
    private static final long LLM_TIMEOUT_MINUTES = 4;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final DedupService dedupService;
    private final PromptConfig promptConfig;

    public ExtractionService(@Autowired ObjectMapper objectMapper,
                             @Autowired @Qualifier("qwen7bInsChatClient") ChatClient chatClient,
                             @Autowired DedupService dedupService,
                             @Autowired PromptConfig promptConfig) {

        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
        this.dedupService = dedupService;
        this.promptConfig = promptConfig;
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

        // 全局收集（全部使用 Neo4j model）
        List<Entity> allRawEntities = Collections.synchronizedList(new ArrayList<>());
        List<Entity> allDocEntities = Collections.synchronizedList(new ArrayList<>());
        // 每个 chunk 内的实体间关系（用实体名称标识，消歧后合并）
        List<Relation> allRawRelations = Collections.synchronizedList(new ArrayList<>());


        try {
            List<DoFile> fileList = TextUtils.readJsonlToList(inputPath, DoFile.class);
            log.info("共需要处理[{}]个文档分块", fileList.size());

            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            int total = fileList.size();

            // =========================
            // 🚀 Phase 1: 每个 chunk 抽取实体 + 同 chunk 内实体间关系
            // =========================
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DoFile file : fileList) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    boolean success = false;
                    try {
                        mainSemaphore.acquire();
                        try {
                            processSingleFile(file, allRawEntities, allDocEntities, allRawRelations);
                            success = true;
                        } finally {
                            mainSemaphore.release();
                        }
                    } catch (Exception e) {
                        if (isTimeoutOrNetworkError(e)) {
                            log.warn("LLM调用超时/网络异常，加入重试队列: chunkId={}", file.getChunkId());
                        } else {
                            log.error("首次处理失败，加入重试队列: {}", file.getFileId(), e);
                        }
                        retryQueue.add(file);
                    }

                    if (success) successCount.incrementAndGet();
                    int current = processedCount.incrementAndGet();
                    log.info("[Phase 1] 进度：{}/{} | 成功：{} | 待重试：{}",
                            current, total, successCount.get(), retryQueue.size());
                    log.info("文档[{}]处理完成", file.getChunkId());

                }, executor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("==================================================");
            log.info("✅ Phase 1 完成！成功：{} | 待重试：{} | 原始关系数：{}",
                    successCount.get(), retryQueue.size(), allRawRelations.size());
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
                                    processSingleFile(file, allRawEntities, allDocEntities, allRawRelations);
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

//            // =========================
//            // Checkpoint: 保存 Phase 1 结果（避免后续阶段 LLM 挂了丢失数据）
//            // =========================
//            log.info("[Checkpoint] 保存 Phase 1 结果...");
//            TextUtils.writeJsonl(outputPath + "/_checkpoint_raw_entities.jsonl", allRawEntities);
//            TextUtils.writeJsonl(outputPath + "/_checkpoint_doc_entities.jsonl", allDocEntities);
//            TextUtils.writeJsonl(outputPath + "/_checkpoint_raw_relations.jsonl", allRawRelations);
//            log.info("[Checkpoint] Phase 1 结果已落盘: 原始实体{} | 文档实体{} | 原始关系{}",
//                    allRawEntities.size(), allDocEntities.size(), allRawRelations.size());

            // =========================
            // Phase 2: 全局消歧去重
            // =========================
            log.info("==================================================");
            log.info("Phase 2: 开始全局实体消歧去重，原始实体数: {}", allRawEntities.size());
            List<Entity> dedupEntities = dedupService.deduplicate(allRawEntities);
            log.info("消歧后实体数: {}", dedupEntities.size());

//            // Checkpoint: 保存 Phase 2 消歧结果（Phase 2.5 又要调 LLM，先落盘）
//            log.info("[Checkpoint] 保存 Phase 2 消歧结果...");
//            TextUtils.writeJsonl(outputPath + "/_checkpoint_dedup_entities.jsonl", dedupEntities);
//            log.info("[Checkpoint] Phase 2 消歧结果已落盘: {} 个实体", dedupEntities.size());

            // =========================
            // Phase 2.5: 对去重后拼接的描述进行 LLM 精炼总结
            // =========================
            log.info("开始始进行实体描述合并");
            dedupEntities = refineEntityDescriptions(dedupEntities);
            log.info("🔬 描述精炼后内容实体数: {}", dedupEntities.size());

            // =========================
            // 🔬 Phase 2.6: 合并同一文档多个chunk的文档实体（同一fileId只保留一个）
            // =========================
            List<Entity> mergedDocEntities = mergeDocEntities(allDocEntities);
            log.info("文档实体合并后数量: {}", mergedDocEntities.size());

//            // Checkpoint: 保存 Phase 2.5/2.6 精炼+合并结果
//            log.info("[Checkpoint] 保存 Phase 2.5/2.6 精炼结果...");
//            List<Entity> allEntitiesForCheckpoint = new ArrayList<>(mergedDocEntities);
//            allEntitiesForCheckpoint.addAll(dedupEntities);
//            TextUtils.writeJsonl(outputPath + "/_checkpoint_all_entities.jsonl", allEntitiesForCheckpoint);
//            log.info("[Checkpoint] 实体精炼+合并结果已落盘: {} 个实体", allEntitiesForCheckpoint.size());

            // 构建 entityName → entityId 映射
            Map<String, String> nameToIdMap = dedupService.buildNameToIdMap(dedupEntities);

            // =========================
            // Phase 3: 消歧后合并关系 + 建立文档-实体包含关系
            // =========================
            log.info("==================================================");
            log.info("Phase 3: 合并关系，原始关系数: {}", allRawRelations.size());

            // 3.1 实体间关系：用消歧后的 entityId 替换 sourceName/targetName（名称不变），合并重复关系
            List<Relation> mergedRelations = mergeRelationsAfterDedup(allRawRelations, nameToIdMap);
            log.info("实体间关系合并后: {}", mergedRelations.size());

            // 3.2 文档-实体包含关系
            List<Relation> docRelations = buildDocEntityRelations(allRawEntities, nameToIdMap, mergedDocEntities);
            log.info("文档-实体包含关系: {}", docRelations.size());

            // 合并所有关系
            List<Relation> allRelations = new ArrayList<>();
            allRelations.addAll(mergedRelations);
            allRelations.addAll(docRelations);
            log.info("Phase 3 完成，总关系数: {}", allRelations.size());

            // =========================
            // 写入 JSONL（全部用 Neo4j model）
            // =========================
            List<Entity> allEntities = new ArrayList<>();
            allEntities.addAll(mergedDocEntities);
            allEntities.addAll(dedupEntities);

            TextUtils.writeJsonl(outputPath + "/all_entities.jsonl", allEntities);
            TextUtils.writeJsonl(outputPath + "/all_relations.jsonl", allRelations);

            log.info("实体写入完成: {} (文档属性: {}, 内容实体: {})",
                    allEntities.size(), mergedDocEntities.size(), dedupEntities.size());
            log.info("关系写入完成: {}", allRelations.size());

            // =========================
            // 最终统计
            // =========================
            log.info("\n");
            log.info("========================================================");
            log.info("                  任务全部完成！");
            log.info("========================================================");
            log.info("总文档数：{}", total);
            log.info("最终成功：{}", successCount.get());
            log.info("最终失败：{}", failCount.get());
            log.info("成功率：{}%", String.format("%.2f", successCount.get() * 100.0 / total));
            log.info("消歧前实体数：{}", allRawEntities.size());
            log.info("消歧后实体数：{}", dedupEntities.size());
            log.info("关系数：{}", allRelations.size());
            log.info("========================================================");

        } catch (Exception e) {
            log.error("实体关系抽取任务执行异常", e);
        }
    }

    /**
     * 判断异常是否为 LLM 调用超时或网络异常（这些是预期内的可恢复错误，无需 ERROR 日志）
     */
    private boolean isTimeoutOrNetworkError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String className = cause.getClass().getName();
            if (className.contains("Timeout") || className.contains("Cancelled")
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 单个 chunk 的抽取逻辑（Phase 1）：
     * 1. 抽取文档属性实体 → Neo4j Entity（文档维度，不需要消歧）
     * 2. 抽取内容实体 → Neo4j Entity（收集到全局，后续消歧）
     * 3. 抽取同 chunk 内内容实体间关系 → Neo4j Relation（用实体名称标识，消歧后合并）
     *
     * @param file              当前 chunk
     * @param allRawEntities    全局原始实体收集（Neo4j Entity）
     * @param allDocEntities    全局文档属性实体收集（Neo4j Entity）
     * @param allRawRelations   全局原始关系收集（Neo4j Relation，用实体名称标识）
     */
    private void processSingleFile(DoFile file,
                                   List<Entity> allRawEntities,
                                   List<Entity> allDocEntities,
                                   List<Relation> allRawRelations) throws Exception {

        log.info("正在处理文件chunkId:[{}]", file.getChunkId());

        // ========== 1️⃣ 抽取文档节点 ==========
        String file_Node_prompt = String.format(promptConfig.fileNodeExtraction(), file.getFileName(), file.getContent());
        BeanOutputConverter<EntityResult> fileEntityConverter = new BeanOutputConverter<>(EntityResult.class);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, fileEntityConverter.getJsonSchema()))
                .build();

        EntityResult docEntityResult = CompletableFuture
                .supplyAsync(() -> chatClient.prompt()
                        .user(file_Node_prompt)
                        .options(options)
                        .call()
                        .entity(EntityResult.class))
                .orTimeout(LLM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .join();

        // 将文档映射为 Neo4j Entity 节点
        if (docEntityResult != null && docEntityResult.entityList() != null && !docEntityResult.entityList().isEmpty()) {
            com.bistu.hkgrag.llm.model.extraction.Entity docEntity = docEntityResult.entityList().get(0);
            Entity neoDocEntity = new Entity();
            neoDocEntity.setEntityId(file.getFileId());
            neoDocEntity.setEntityName(docEntity.entityName());
            neoDocEntity.setNodeType(Entity.Type.DOCUMENT_ENTITY);
            neoDocEntity.setEntityType(docEntity.entityType());
            neoDocEntity.setEntityDescription(docEntity.entityDescription());
            neoDocEntity.setEntityInfo(docEntity.entityInfo());
            neoDocEntity.setFileId(file.getFileId());
            neoDocEntity.setChunkId(file.getChunkId());
            allDocEntities.add(neoDocEntity);
        }

        // ========== 2️⃣ 抽取内容实体 ==========
        String content_Extraction_prompt = String.format(promptConfig.contentEntityExtraction(), file.getContent());
        BeanOutputConverter<EntityResult> contentEntityConverter = new BeanOutputConverter<>(EntityResult.class);
        OpenAiChatOptions contentOptions = OpenAiChatOptions.builder()
                .temperature(0.3)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, contentEntityConverter.getJsonSchema()))
                .build();

        EntityResult contentEntityResult = CompletableFuture
                .supplyAsync(() -> chatClient.prompt()
                        .user(content_Extraction_prompt)
                        .options(contentOptions)
                        .call()
                        .entity(EntityResult.class))
                .orTimeout(LLM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .join();

        List<com.bistu.hkgrag.llm.model.extraction.Entity> chunkEntities = contentEntityResult.entityList();

        // 收集到全局原始实体列表（待消歧）：extraction record → Neo4j Entity
        for (com.bistu.hkgrag.llm.model.extraction.Entity e : chunkEntities) {
            Entity neoEntity = new Entity();
            neoEntity.setEntityId(UUID.randomUUID().toString());
            neoEntity.setEntityName(e.entityName().trim());
            neoEntity.setEntityType(e.entityType());
            neoEntity.setEntityDescription(e.entityDescription());
            neoEntity.setEntityInfo(e.entityInfo());
            neoEntity.setNodeType(Entity.Type.CONTENT_ENTITY);
            neoEntity.setFileId(file.getFileId());
            neoEntity.setChunkId(file.getChunkId());
            allRawEntities.add(neoEntity);
        }

        // ========== 2.5️⃣ Gleaning：补充遗漏实体 ==========
        List<com.bistu.hkgrag.llm.model.extraction.Entity> gleanEntities = gleanMissingEntities(
                file.getContent(), chunkEntities);
        for (com.bistu.hkgrag.llm.model.extraction.Entity e : gleanEntities) {
            Entity neoEntity = new Entity();
            neoEntity.setEntityId(UUID.randomUUID().toString());
            neoEntity.setEntityName(e.entityName().trim());
            neoEntity.setEntityType(e.entityType());
            neoEntity.setEntityDescription(e.entityDescription());
            neoEntity.setEntityInfo(e.entityInfo());
            neoEntity.setNodeType(Entity.Type.CONTENT_ENTITY);
            neoEntity.setFileId(file.getFileId());
            neoEntity.setChunkId(file.getChunkId());
            allRawEntities.add(neoEntity);
            chunkEntities.add(e);
        }
        if (!gleanEntities.isEmpty()) {
            log.info("🔍 Gleaning 补充遗漏实体 {} 个: chunkId={}", gleanEntities.size(), file.getChunkId());
        }

        // 构建实体索引列表（用于 LLM 抽取实体间关系）
        if (chunkEntities.isEmpty()) return;

        List<Map<String, Object>> entityIndexList = new ArrayList<>();
        for (com.bistu.hkgrag.llm.model.extraction.Entity e : chunkEntities) {
            Map<String, Object> indexEntry = new HashMap<>();
            indexEntry.put("name", e.entityName().trim());
            indexEntry.put("type", e.entityType());
            indexEntry.put("description", e.entityDescription());
            indexEntry.put("info", e.entityInfo());
            entityIndexList.add(indexEntry);
        }

        // ========== 3️⃣ 抽取同 chunk 内内容实体间关系 ==========
        String entityTriplesJson = objectMapper.writeValueAsString(entityIndexList);
        String entities_Relation_prompt = String.format(promptConfig.entitiesRelationExtraction(), file.getContent(), entityTriplesJson);
        BeanOutputConverter<RelationResult> relationConverter = new BeanOutputConverter<>(RelationResult.class);
        OpenAiChatOptions relationOptions = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, relationConverter.getJsonSchema()))
                .build();

        RelationResult relationResult = CompletableFuture
                .supplyAsync(() -> chatClient.prompt()
                        .user(entities_Relation_prompt)
                        .options(relationOptions)
                        .call()
                        .entity(RelationResult.class))
                .orTimeout(LLM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .join();

        for (com.bistu.hkgrag.llm.model.extraction.Relation rel : relationResult.relationList()) {
            String srcName = rel.sourceName() != null ? rel.sourceName().trim() : "";
            String tgtName = rel.targetName() != null ? rel.targetName().trim() : "";
            // 过滤掉源实体或目标实体为空的关系（LLM 偶发输出不完整）
            if (srcName.isEmpty() || tgtName.isEmpty()) {
                log.warn("跳过不完整关系: sourceName={}, targetName={}", srcName, tgtName);
                continue;
            }
            Relation neoRel = new Relation();
            neoRel.setSourceId(srcName);
            neoRel.setTargetId(tgtName);
            neoRel.setSourceName(srcName);
            neoRel.setTargetName(tgtName);
            // 使用 LLM 返回的泛化 keywords，如果 LLM 未返回则 fallback 为原始名称拼接
            String keywords = rel.keywords();
            if (keywords == null || keywords.isBlank()) {
                keywords = srcName + "+" + tgtName;
            }
            neoRel.setKeywords(keywords.trim());
            neoRel.setRelationDescription(rel.description());
            allRawRelations.add(neoRel);
        }
    }

    /**
     * 消歧后合并实体间关系：
     * 将关系中的 sourceId/targetId（实体名称）通过 nameToIdMap 替换为消歧后的 entityId，
     * 合并完全重复的关系（sourceId + targetId + keywords 相同的去重）
     */
    private List<Relation> mergeRelationsAfterDedup(
            List<Relation> rawRelations,
            Map<String, String> nameToIdMap) {

        // 用 (sourceId, targetId, keywords) 去重
        Map<String, Relation> mergedMap = new LinkedHashMap<>();

        for (Relation rel : rawRelations) {
            String sourceName = rel.getSourceId();  // 原始关系中存的是实体名称
            String targetName = rel.getTargetId();

            String sourceId = nameToIdMap.get(sourceName);
            String targetId = nameToIdMap.get(targetName);
            if (sourceId == null || targetId == null) continue;  // 实体不存在了
            if (sourceId.equals(targetId)) continue;             // 自环跳过

            String key = sourceId + "|" + targetId + "|" + rel.getKeywords();
            if (!mergedMap.containsKey(key)) {
                Relation merged = new Relation();
                merged.setSourceId(sourceId);
                merged.setTargetId(targetId);
                merged.setSourceName(rel.getSourceName());
                merged.setTargetName(rel.getTargetName());
                merged.setKeywords(rel.getKeywords());
                merged.setRelationDescription(rel.getRelationDescription());
                mergedMap.put(key, merged);
            }
            // 如果已存在，跳过（去重）
        }

        return new ArrayList<>(mergedMap.values());
    }

    /**
     * 建立文档-内容实体的"包含"关系。
     * 消歧后执行，按 fileId 分组获取每个 chunk 的实体。
     *
     * @param allRawEntities    原始内容实体列表
     * @param nameToIdMap       实体名称 → 实体ID 映射
     * @param mergedDocEntities 合并后的文档实体列表（用于获取文档名称）
     */
    private List<Relation> buildDocEntityRelations(
            List<Entity> allRawEntities,
            Map<String, String> nameToIdMap,
            List<Entity> mergedDocEntities) {

        // 构建 fileId → 文档实体名称 映射
        Map<String, String> docNameMap = mergedDocEntities.stream()
                .collect(Collectors.toMap(Entity::getEntityId, Entity::getEntityName, (a, b) -> a));

        // 按 fileId 分组，得到每个 chunk 对应的原始实体列表
        Map<String, List<Entity>> entitiesByFileId = allRawEntities.stream()
                .collect(Collectors.groupingBy(Entity::getFileId));

        // 用 (sourceId, targetId) 去重（一个文档对同一个实体只保留一条包含关系）
        Map<String, Relation> docRelMap = new LinkedHashMap<>();

        for (Map.Entry<String, List<Entity>> entry : entitiesByFileId.entrySet()) {
            String fileId = entry.getKey();
            List<Entity> chunkEntities = entry.getValue();

            String docName = docNameMap.getOrDefault(fileId, fileId);

            for (Entity entity : chunkEntities) {
                String entityName = entity.getEntityName();
                String entityId = nameToIdMap.get(entityName);
                if (entityId == null) continue;

                String key = fileId + "|" + entityId;
                if (!docRelMap.containsKey(key)) {
                    Relation docRel = new Relation();
                    docRel.setSourceId(fileId);
                    docRel.setTargetId(entityId);
                    docRel.setSourceName(docName);
                    docRel.setTargetName(entityName);
                    docRel.setKeywords("doc+" + entityName);
                    docRel.setRelationDescription("包含");
                    docRelMap.put(key, docRel);
                }
            }
        }

        return new ArrayList<>(docRelMap.values());
    }

    /**
     * 对消歧去重后描述包含拼接符号（+ 或 |）的实体，调用 LLM 精炼总结描述。
     * 只处理描述中包含拼接符号的实体，单句描述直接跳过。
     */
    private List<Entity> refineEntityDescriptions(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return entities;

        List<Entity> refined = new ArrayList<>();
        int refinedCount = 0;
        Semaphore semaphore = new Semaphore(5);

        for (Entity entity : entities) {
            String desc = entity.getEntityDescription();
            if (desc == null || (!desc.contains(" + ") && !desc.contains(" | "))) {
                refined.add(entity);
                continue;
            }

            try {
                semaphore.acquire();
                try {
                    String summaryPrompt = String.format(
                            promptConfig.descriptionSummary(), desc);
                    String summarized = chatClient.prompt().user(summaryPrompt).call().content();
                    if (summarized != null && !summarized.isBlank()) {
                        entity.setEntityDescription(summarized.trim());
                        refinedCount++;
//                        log.info("实体描述精炼: [{}] -> {}", entity.getEntityName(), summarized.trim());
                    }
                } finally {
                    semaphore.release();
                }
            } catch (Exception e) {
                log.warn("实体[{}]描述精炼失败，保留原始描述", entity.getEntityName());
            }
            refined.add(entity);
        }

        log.info("描述精炼完成: 共处理 {} 个拼接描述", refinedCount);
        return refined;
    }

    /**
     * 合并同一文档多个chunk抽取的文档实体（同一fileId只保留一个）。
     * entityInfo 直接拼接去重，entityDescription 调 LLM 精炼为通顺描述。
     */
    private List<Entity> mergeDocEntities(List<Entity> docEntities) {
        if (docEntities == null || docEntities.isEmpty()) return docEntities;

        // 按 entityId（即 fileId）分组
        Map<String, List<Entity>> grouped = docEntities.stream()
                .collect(Collectors.groupingBy(Entity::getEntityId, LinkedHashMap::new, Collectors.toList()));

        List<Entity> merged = new ArrayList<>();
        Semaphore semaphore = new Semaphore(5);

        for (Map.Entry<String, List<Entity>> entry : grouped.entrySet()) {
            List<Entity> sameFileEntities = entry.getValue();
            if (sameFileEntities.size() == 1) {
                merged.add(sameFileEntities.get(0));
                continue;
            }

            // 多个chunk：取第一个作为基准
            Entity base = sameFileEntities.get(0);

            // entityInfo：去重后直接拼接
            String mergedInfo = sameFileEntities.stream()
                    .map(Entity::getEntityInfo)
                    .filter(info -> info != null && !info.isBlank())
                    .distinct()
                    .collect(Collectors.joining("; "));
            base.setEntityInfo(mergedInfo);

            // entityDescription：去重后调 LLM 精炼
            List<String> distinctDescs = sameFileEntities.stream()
                    .map(Entity::getEntityDescription)
                    .filter(d -> d != null && !d.isBlank())
                    .distinct()
                    .toList();

            if (distinctDescs.size() > 1) {
                String combinedDesc = String.join(" + ", distinctDescs);
                try {
                    semaphore.acquire();
                    try {
                        String summaryPrompt = String.format(
                                promptConfig.descriptionSummary(), combinedDesc);
                        String summarized = chatClient.prompt().user(summaryPrompt).call().content();
                        if (summarized != null && !summarized.isBlank()) {
                            base.setEntityDescription(summarized.trim());
                            log.info("文档实体[{}]描述合并精炼完成: {} 个chunk → 1", base.getEntityName(), sameFileEntities.size());
                        }
                    } finally {
                        semaphore.release();
                    }
                } catch (Exception e) {
                    log.warn("文档实体[{}]描述合并失败，使用拼接描述", base.getEntityName());
                    base.setEntityDescription(combinedDesc);
                }
            }

            merged.add(base);
        }

        return merged;
    }

    /**
     * Gleaning：让 LLM 检查文档内容，补充第一轮遗漏的实体
     */
    private List<com.bistu.hkgrag.llm.model.extraction.Entity> gleanMissingEntities(
            String content,
            List<com.bistu.hkgrag.llm.model.extraction.Entity> existingEntities) {

        // 构建已抽取实体的摘要列表
        List<String> existingNames = existingEntities.stream()
                .map(com.bistu.hkgrag.llm.model.extraction.Entity::entityName)
                .toList();
        String existingJson;
        try {
            existingJson = objectMapper.writeValueAsString(existingNames);
        } catch (Exception e) {
            existingJson = existingNames.toString();
        }

        String gleaningPrompt = String.format(promptConfig.entityGleaning(), content, existingJson);
        BeanOutputConverter<EntityResult> converter = new BeanOutputConverter<>(EntityResult.class);
        OpenAiChatOptions gleanOptions = OpenAiChatOptions.builder()
                .temperature(0.1)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema()))
                .build();

        try {
            EntityResult result = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .user(gleaningPrompt)
                            .options(gleanOptions)
                            .call()
                            .entity(EntityResult.class))
                    .orTimeout(LLM_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                    .join();

            if (result != null && result.entityList() != null) {
                return result.entityList();
            }
        } catch (Exception e) {
            log.warn("Gleaning 失败: {}", e.getMessage());
        }
        return List.of();
    }
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
        Semaphore semaphore = new Semaphore(3);
        // 每次请求之间的最小间隔（毫秒），防止 LLM 服务被打爆
        final long THROTTLE_MS = 500;
        // 单次 LLM 调用的最大重试次数
        final int MAX_RETRY = 3;

        try {
            log.info("开始提取文档主题");

//            // ✅ 1. 读取数据
//            List<DoFile> fileList = TextUtils.readJson(inputPath);
            // ✅ 1. 读取数据（JSONL 格式，每行一个 JSON 对象）
            List<DoFile> fileList = TextUtils.readJsonlToList(inputPath, DoFile.class);

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

                            boolean chunkSuccess = false;
                            for (int retry = 0; retry < MAX_RETRY; retry++) {
                                semaphore.acquire();
                                try {
                                    String promptStr = String.format(
                                            promptConfig.fileTopicExtraction(),
                                            chunk.getFileName(),
                                            chunk.getContent()
                                    );

                                    ChatResponse response = chatClient.prompt(promptStr)
                                            .call()
                                            .chatResponse();
                                    String topic = response.getResult().getOutput().getText().trim();

                                    if (topic != null && !topic.isEmpty()) {
                                        chunk.setTopic(topic);
                                        chunkTopics.add(topic);
                                        successChunks.incrementAndGet();
                                        chunkSuccess = true;
                                    }
                                    break; // 请求成功，跳出重试循环

                                } catch (Exception e) {
                                    if (retry < MAX_RETRY - 1) {
                                        long backoff = (long) Math.pow(2, retry) * 1000; // 1s, 2s, 4s
                                        log.warn("Chunk[{}] LLM调用失败，第{}次重试(共{}次)，等待{}ms: {}",
                                                chunk.getChunkId(), retry + 1, MAX_RETRY, backoff, e.getMessage());
                                        try {
                                            Thread.sleep(backoff);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    } else {
                                        log.error("Chunk[{}] 重试{}次后仍失败: {}",
                                                chunk.getChunkId(), MAX_RETRY, e.getMessage());
                                    }
                                } finally {
                                    semaphore.release();
                                }

                                // 释放信号量后稍等，避免瞬间并发冲击 LLM 服务
                                try {
                                    Thread.sleep(THROTTLE_MS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }

                            if (!chunkSuccess) {
                                failChunks.incrementAndGet();
                                failedChunkIds.add(chunk.getChunkId());
                            }
                        }

                        // ========= Step2：文档级主题汇总 =========
                        String finalTopic = "未知主题";

                        if (chunkTopics.isEmpty()) {
                            finalTopic = "";
                        } else if (chunkTopics.size() == 1) {
                            finalTopic = chunkTopics.get(0);
                        } else {
                            boolean summarySuccess = false;
                            for (int retry = 0; retry < MAX_RETRY; retry++) {
                                semaphore.acquire();
                                try {
                                    String summaryPrompt = String.format(
                                            promptConfig.fileTopicSummaryExtraction(),
                                            chunks.get(0).getFileName(),
                                            String.join("\n", chunkTopics)
                                    );

                                    ChatResponse response = chatClient.prompt(summaryPrompt)
                                            .call()
                                            .chatResponse();
                                    finalTopic = response.getResult().getOutput().getText().trim();
                                    summarySuccess = true;
                                    break;

                                } catch (Exception e) {
                                    if (retry < MAX_RETRY - 1) {
                                        long backoff = (long) Math.pow(2, retry) * 1000;
                                        log.warn("文档[{}]主题汇总失败，第{}次重试(共{}次)，等待{}ms: {}",
                                                fileId, retry + 1, MAX_RETRY, backoff, e.getMessage());
                                        try {
                                            Thread.sleep(backoff);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    } else {
                                        log.error("文档[{}]主题汇总重试{}次后仍失败: {}",
                                                fileId, MAX_RETRY, e.getMessage());
                                    }
                                } finally {
                                    semaphore.release();
                                }
                                try {
                                    Thread.sleep(THROTTLE_MS);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            if (!summarySuccess) {
                                finalTopic = "未知主题";
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
}
