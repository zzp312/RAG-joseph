package com.xushu.rag.service.impl;

import com.xushu.rag.service.IRerankStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 版本优先重排序策略 —— IRerankStrategy 默认实现
 * <p>
 * 逻辑：同一source的文档按版本号降序排列，新版本优先展示。
 * Phase 2 将提供 LLMRerankStrategy 实现，按语义相关性重排。
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component("versionFirstRerankStrategy")
public class VersionFirstRerankStrategy implements IRerankStrategy {

    @Override
    public List<Document> rerank(List<Document> documents, Map<String, Object> context) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        // 按source分组
        Map<String, List<Document>> docsBySource = documents.stream()
                .collect(Collectors.groupingBy(
                        doc -> {
                            Object source = doc.getMetadata().get("source");
                            return source != null ? source.toString() : "unknown";
                        },
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Document> rankedDocs = new ArrayList<>();
        for (Map.Entry<String, List<Document>> entry : docsBySource.entrySet()) {
            List<Document> sourceDocs = entry.getValue();
            // 组内按版本降序
            sourceDocs.sort((doc1, doc2) -> {
                String v1 = (String) doc1.getMetadata().getOrDefault("version", "");
                String v2 = (String) doc2.getMetadata().getOrDefault("version", "");
                return compareVersions(v2, v1);
            });
            rankedDocs.addAll(sourceDocs);
        }

        // 全局按版本降序
        rankedDocs.sort((doc1, doc2) -> {
            String v1 = (String) doc1.getMetadata().getOrDefault("version", "");
            String v2 = (String) doc2.getMetadata().getOrDefault("version", "");
            return compareVersions(v2, v1);
        });

        return rankedDocs;
    }

    /**
     * 版本号比较
     */
    private int compareVersions(String v1, String v2) {
        if (v1.equals(v2)) return 0;
        try {
            String[] parts1 = v1.replace("v", "").split("\\.");
            String[] parts2 = v2.replace("v", "").split("\\.");
            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                long num1 = i < parts1.length ? Long.parseLong(parts1[i]) : 0;
                long num2 = i < parts2.length ? Long.parseLong(parts2[i]) : 0;
                if (num1 != num2) return Long.compare(num1, num2);
            }
        } catch (Exception e) {
            log.warn("版本号比较失败: {} vs {}", v1, v2);
        }
        return v1.compareTo(v2);
    }
}
