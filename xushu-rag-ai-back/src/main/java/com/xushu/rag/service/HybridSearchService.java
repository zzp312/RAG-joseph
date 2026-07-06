package com.xushu.rag.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务：Dense(Vector) + BM25(全文检索) 双路召回，RRF 合并。
 * <p>BM25 通过 V2 SDK 的 EmbeddedText 传查询文本，服务端 BM25 function 自动编码。</p>
 *
 * @author Joseph
 */
@Slf4j
@Service
public class HybridSearchService {

    private static final int RRF_K = 60;
    private static final int COARSE_FACTOR = 2;

    private final VectorStore vectorStore;
    private final MilvusClientV2 v2Client;
    private final String collectionName;

    public HybridSearchService(
            VectorStore vectorStore,
            MilvusClientV2 v2Client,
            @Value("${spring.ai.vectorstore.milvus.collectionName:vector_store_v2}") String collectionName) {
        this.vectorStore = vectorStore;
        this.v2Client = v2Client;
        this.collectionName = collectionName;
        log.info("[HybridSearch] Dense + BM25(EmbeddedText) 混合检索就绪, collection={}", collectionName);
    }

    // ==================== 入口 ====================

    public List<Document> search(String query, int topK, String filterExpression) {
        // ① Dense 向量检索
        SearchRequest.Builder denseBuilder = SearchRequest.builder()
                .query(query).topK(topK * COARSE_FACTOR).similarityThreshold(0.1);
        if (filterExpression != null && !filterExpression.isEmpty()) {
            denseBuilder.filterExpression(filterExpression);
        }
        List<Document> denseDocs = safeDenseSearch(denseBuilder.build());

        // ② BM25 全文检索（失败/空结果均回退到 Dense，不永久禁用）
        List<Document> sparseDocs;
        try {
            sparseDocs = bm25Search(query, topK * COARSE_FACTOR, filterExpression);
        } catch (Exception e) {
            log.warn("[HybridSearch] BM25 失败，降级纯Dense: {}", e.getMessage());
            return denseDocs.stream().limit(topK).collect(Collectors.toList());
        }

        if (sparseDocs.isEmpty()) {
            return denseDocs.stream().limit(topK).collect(Collectors.toList());
        }

        // ③ RRF 合并
        List<Document> merged = mergeWithRRF(denseDocs, sparseDocs, topK);
        log.info("[HybridSearch] Dense={}, BM25={}, 合并={}", denseDocs.size(), sparseDocs.size(), merged.size());
        return merged;
    }

    // ==================== Dense ====================

    private List<Document> safeDenseSearch(SearchRequest request) {
        try {
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("[HybridSearch] Dense失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== BM25 ====================

    /**
     * BM25 全文检索：用 EmbeddedText 包装查询文本，
     * Milvus 服务端 BM25 function 自动将文本转为稀疏向量匹配。
     */
    @SuppressWarnings("unchecked")
    private List<Document> bm25Search(String query, int topK, String filterExpression) {
        // 不设 searchParams，交给服务端按 BM25 默认策略处理

        // 构造搜索请求：EmbeddedText 传递原始文本
        SearchReq.SearchReqBuilder<?, ?> builder = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("sparse_vector")
                .data(Collections.singletonList(new EmbeddedText(query)))
                .outputFields(Arrays.asList("doc_id", "content", "metadata"))
                .topK(topK);

        // 附加过滤条件（非顶层字段需改写为 metadata JSON 字段语法）
        if (filterExpression != null && !filterExpression.isEmpty()) {
            String converted = toMetadataFilter(filterExpression);
            log.debug("[HybridSearch] BM25 filter: {} → {}", filterExpression, converted);
            builder.filter(converted);
        }

        SearchResp resp = v2Client.search(builder.build());

        // 解析结果
        List<Document> docs = new ArrayList<>();
        if (resp.getSearchResults() != null) {
            for (List<SearchResp.SearchResult> results : resp.getSearchResults()) {
                if (results == null) continue;
                for (SearchResp.SearchResult result : results) {
                    Map<String, Object> entity = result.getEntity();
                    if (entity == null) continue;

                    String docId = Objects.toString(entity.get("doc_id"), "");
                    String content = Objects.toString(entity.get("content"), "");

                    Map<String, Object> docMeta = new HashMap<>();
                    Object metaObj = entity.get("metadata");
                    if (metaObj instanceof Map) {
                        docMeta.putAll((Map<String, Object>) metaObj);
                    }
                    docMeta.put("bm25_score", result.getScore());

                    docs.add(new Document(docId, content, docMeta));
                }
            }
        }
        log.debug("[HybridSearch] BM25 '{}' → {} 结果", query.substring(0, Math.min(20, query.length())), docs.size());
        return docs;
    }

    // ==================== RRF ====================

    private List<Document> mergeWithRRF(List<Document> denseDocs, List<Document> sparseDocs, int topK) {
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        for (int i = 0; i < denseDocs.size(); i++) {
            scoreMap.merge(denseDocs.get(i).getId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < sparseDocs.size(); i++) {
            scoreMap.merge(sparseDocs.get(i).getId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> findDocumentById(e.getKey(), denseDocs, sparseDocs))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Document findDocumentById(String id, List<Document>... docLists) {
        for (List<Document> docs : docLists) {
            for (Document doc : docs) {
                if (id.equals(doc.getId())) return doc;
            }
        }
        return null;
    }

    // ==================== 图片关联查询 ====================

    /**
     * 按 source+version 组合拉取同文档的所有 IMAGE 文档
     * <p>用于 Rerank 后补全图片：文本 chunk 已精排命中，但其关联的图片可能因 embedding 语义距离远而被过滤。
     * 此方法用纯 filter 查询（不依赖向量相似度），确保同文档的图片一定被带回。</p>
     *
     * @param sourceVersionPairs source+version 组合集合，格式 "source|version"
     * @return 对应文档的所有 IMAGE 类型 Document
     */
    public List<Document> fetchImagesBySourceVersion(Set<String> sourceVersionPairs) {
        if (sourceVersionPairs == null || sourceVersionPairs.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 OR 过滤条件：(source==X && version==Y) || (source==Z && version==W) || ...
        StringBuilder filter = new StringBuilder();
        boolean first = true;
        for (String sv : sourceVersionPairs) {
            String[] parts = sv.split("\\|", 2);
            String source = parts[0];
            String version = parts.length > 1 ? parts[1] : "";
            if (!first) filter.append(" || ");
            filter.append("(")
                    .append("metadata[\"source\"] == \"").append(escapeFilterString(source)).append("\"")
                    .append(" && metadata[\"version\"] == \"").append(escapeFilterString(version)).append("\"")
                    .append(" && metadata[\"chunk_type\"] == \"IMAGE\"")
                    .append(")");
            first = false;
        }

        try {
            QueryReq queryReq = QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter.toString())
                    .outputFields(Arrays.asList("doc_id", "content", "metadata"))
                    .limit(200)
                    .build();
            QueryResp resp = v2Client.query(queryReq);

            List<Document> images = new ArrayList<>();
            if (resp.getQueryResults() != null) {
                for (QueryResp.QueryResult result : resp.getQueryResults()) {
                    Map<String, Object> entity = result.getEntity();
                    if (entity == null) continue;

                    String docId = Objects.toString(entity.get("doc_id"), "");
                    String content = Objects.toString(entity.get("content"), "");

                    Map<String, Object> docMeta = new HashMap<>();
                    Object metaObj = entity.get("metadata");
                    if (metaObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metaMap = (Map<String, Object>) metaObj;
                        docMeta.putAll(metaMap);
                    }

                    images.add(new Document(docId, content, docMeta));
                }
            }
            log.info("[HybridSearch] 图片关联查询: {}个(source,version) → {}张图片",
                    sourceVersionPairs.size(), images.size());
            return images;
        } catch (Exception e) {
            log.warn("[HybridSearch] 图片关联查询失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 转义过滤表达式中的特殊字符（引号等）
     */
    private String escapeFilterString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== Filter 转换 ====================

    /** 顶层字段，不在 metadata JSON 中 */
    private static final Set<String> TOP_FIELDS = Set.of("doc_id", "content", "metadata", "embedding", "sparse_vector");

    /**
     * 将 Spring AI 格式过滤表达式转为 Milvus JSON 字段语法。
     * 例如: "kb_id in [9]" → "metadata[\"kb_id\"] in [9]"
     *       "source in [\"a.pdf\"]" → "metadata[\"source\"] in [\"a.pdf\"]"
     */
    private String toMetadataFilter(String expr) {
        // 匹配: 非顶层 fieldName 后面紧跟 " in [" 或 " ==" 或 " !=" 或 " like " 或 " >" 等
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\b(\\w+)\\b(\\s*(?:in\\s*\\[|==|!=|like\\s+|>=|<=|>|<))");
        return p.matcher(expr).replaceAll(r -> {
            String field = r.group(1);
            String op = r.group(2);
            if (TOP_FIELDS.contains(field) || field.equals("metadata")) {
                return field + op;
            }
            return "metadata[\"" + field + "\"]" + op;
        });
    }
}
