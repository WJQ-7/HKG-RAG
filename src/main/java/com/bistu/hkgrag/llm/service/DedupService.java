package com.bistu.hkgrag.llm.service;

import com.bistu.hkgrag.dao.neo4j.model.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 跨 chunk 实体消歧/去重服务
 *
 * 两阶段去重：
 *   Phase 1: 同文档内块间去重 —— 同 fileId、同 entityName 的实体合并
 *   Phase 2: 跨文档去重 —— 不同 fileId 但同 entityName 的实体合并
 */
@Service
public class DedupService {

    private static final Logger log = LoggerFactory.getLogger(DedupService.class);

    /**
     * 对跨 chunk 的所有实体执行消歧去重
     *
     * @param allChunkEntities 所有 chunk 抽取出的原始实体列表（Neo4j Entity）
     * @return 去重后的 Neo4j Entity 列表
     */
    public List<Entity> deduplicate(List<Entity> allChunkEntities) {
        if (allChunkEntities == null || allChunkEntities.isEmpty()) {
            return Collections.emptyList();
        }

        // ========== Phase 1: 同文档内块间去重 ==========
        // 按 (entityName, fileId) 分组
        Map<String, List<Entity>> groupedByFile = allChunkEntities.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEntityName() + "|" + e.getFileId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Entity> fileDeduped = new ArrayList<>();
        for (List<Entity> sameFileEntities : groupedByFile.values()) {
            fileDeduped.add(mergeSameFile(sameFileEntities));
        }
        log.info("[消歧 Phase 1] 原始实体数: {} → 同文档块间去重后: {}",
                allChunkEntities.size(), fileDeduped.size());

        // ========== Phase 2: 跨文档去重 ==========
        // 按 entityName 分组（不同 fileId 但同名）
        Map<String, List<Entity>> groupedByName = fileDeduped.stream()
                .collect(Collectors.groupingBy(
                        Entity::getEntityName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Entity> dedupEntities = new ArrayList<>();
        for (List<Entity> sameNameEntities : groupedByName.values()) {
            dedupEntities.add(mergeCrossFile(sameNameEntities));
        }
        log.info("[消歧 Phase 2] 跨文档去重后: {} 个唯一实体", dedupEntities.size());

        return dedupEntities;
    }

    /**
     * 同文档内块间去重：
     * - 类型：投票决定
     * - 描述：用 | 拼接去重（同一实体在不同块中可能负责不同任务）
     * - info：用 | 拼接去重
     * - fileId：用 | 拼接去重
     * - chunkId：用 | 拼接去重
     */
    private Entity mergeSameFile(List<Entity> entities) {
        if (entities.size() == 1) return entities.get(0);

        Entity merged = new Entity();
        merged.setEntityId(UUID.randomUUID().toString());
        merged.setEntityName(entities.get(0).getEntityName());
        merged.setNodeType(Entity.Type.CONTENT_ENTITY);
        merged.setEntityType(voteType(entities));

        // 描述：去重后用 + 拼接（相同则保留一个，不同则区分）
        String mergedDesc = entities.stream()
                .map(Entity::getEntityDescription)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .collect(Collectors.joining(" + "));
        merged.setEntityDescription(mergedDesc.isEmpty() ? entities.get(0).getEntityName() : mergedDesc);

        // info：去重后用 + 拼接（相同则保留一个，不同则区分）
        String mergedInfo = entities.stream()
                .map(Entity::getEntityInfo)
                .filter(i -> i != null && !i.isBlank())
                .distinct()
                .collect(Collectors.joining(" + "));
        merged.setEntityInfo(mergedInfo.isEmpty() ? null : mergedInfo);

        // fileId 用 | 拼接去重
        String mergedFileId = entities.stream()
                .map(Entity::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("|"));
        merged.setFileId(mergedFileId);

        // chunkId 用 | 拼接去重
        String mergedChunkId = entities.stream()
                .map(Entity::getChunkId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("+"));
        merged.setChunkId(mergedChunkId);

        return merged;
    }

    /**
     * 跨文档去重：
     * - 类型：投票决定
     * - 描述：用 | 拼接（不调 LLM，保留各文档的不同描述）
     * - info：用 | 拼接
     * - fileId：用 | 拼接
     * - chunkId：用 | 拼接
     */
    private Entity mergeCrossFile(List<Entity> entities) {
        if (entities.size() == 1) return entities.get(0);

        Entity merged = new Entity();
        merged.setEntityId(UUID.randomUUID().toString());
        merged.setEntityName(entities.get(0).getEntityName());
        merged.setNodeType(Entity.Type.CONTENT_ENTITY);
        merged.setEntityType(voteType(entities));

        // 跨文档：描述用 | 拼接
        String mergedDesc = entities.stream()
                .map(Entity::getEntityDescription)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .collect(Collectors.joining(" | "));
        merged.setEntityDescription(mergedDesc.isEmpty() ? entities.get(0).getEntityName() : mergedDesc);

        // 跨文档：info 用 | 拼接
        String mergedInfo = entities.stream()
                .map(Entity::getEntityInfo)
                .filter(i -> i != null && !i.isBlank())
                .distinct()
                .collect(Collectors.joining(" | "));
        merged.setEntityInfo(mergedInfo.isEmpty() ? null : mergedInfo);

        // fileId 用 | 拼接
        String mergedFileId = entities.stream()
                .map(Entity::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("|"));
        merged.setFileId(mergedFileId);

        // chunkId 用 | 拼接
        String mergedChunkId = entities.stream()
                .map(Entity::getChunkId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("|"));
        merged.setChunkId(mergedChunkId);

        return merged;
    }

    /**
     * 类型投票：取出现次数最多的类型
     */
    private String voteType(List<Entity> entities) {
        return entities.stream()
                .map(Entity::getEntityType)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("未知");
    }

    /**
     * 构建实体索引 Map（entityName → entityId），供关系抽取使用
     */
    public Map<String, String> buildNameToIdMap(List<Entity> entities) {
        return entities.stream()
                .collect(Collectors.toMap(
                        Entity::getEntityName,
                        Entity::getEntityId,
                        (existing, replacement) -> existing
                ));
    }
}
