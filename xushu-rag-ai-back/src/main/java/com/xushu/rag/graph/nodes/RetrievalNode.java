package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.service.DocumentPageService;
import com.xushu.rag.service.IRerankStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量检索Node（复用现有Small-to-Big回表逻辑）
 * <p>调用Milvus检索 → 去重回表DocumentPageService → 构建父页面文档列表</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class RetrievalNode {

    /** 有filter时的默认topK（搜索空间已缩窄） */
    private static final int TOP_K_FILTERED = 10;
    /** 无filter时的粗召回topK（搜索空间大，需更多候选供Rerank筛选） */
    private static final int TOP_K_UNFILTERED = 30;

    private final VectorStore vectorStore;
    private final DocumentPageService documentPageService;
    /** 主Rerank：qwen3-rerank专用模型（快速、准确） */
    private final IRerankStrategy primaryRerank;
    /** 降级兜底：LLM Rerank（当专用模型API不可用时） */
    private final IRerankStrategy fallbackRerank;

    public RetrievalNode(VectorStore vectorStore,
                         DocumentPageService documentPageService,
                         @Qualifier("qwen3RerankStrategy") IRerankStrategy primaryRerank,
                         @Qualifier("llmRerankStrategy") IRerankStrategy fallbackRerank) {
        this.vectorStore = vectorStore;
        this.documentPageService = documentPageService;
        this.primaryRerank = primaryRerank;
        this.fallbackRerank = fallbackRerank;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        @SuppressWarnings("unchecked")
        List<Long> kbIds = (List<Long>) state.getOrDefault(StateKeys.KB_IDS, null);
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) state.getOrDefault(StateKeys.SOURCES, null);

        // 诊断：打印State中所有key确认参数完整性
        log.info("[Retrieval-Diag] stateKeys={}, question={}",
                state.keySet(),
                ((String) state.getOrDefault(StateKeys.QUESTION, "")).length() > 30 
                    ? ((String) state.get(StateKeys.QUESTION)).substring(0, 30) + "..." 
                    : state.get(StateKeys.QUESTION));

        // 构建过滤条件
        List<String> filterParts = new ArrayList<>();
        if (sources != null && !sources.isEmpty()) {
            filterParts.add("source in " + com.alibaba.fastjson.JSON.toJSONString(sources));
        } else if (kbIds != null && !kbIds.isEmpty()) {
            filterParts.add("kb_id in " + com.alibaba.fastjson.JSON.toJSONString(kbIds));
        }

        String filterExpr = filterParts.isEmpty() ? "无过滤(全文件)" : String.join(" && ", filterParts);
        log.info("[Retrieval] kbIds={}, sources={}, filter={}", kbIds, sources, filterExpr);

        // 无filter时粗召回更多候选(topK=30），交给Rerank精排
        // 有filter时搜索空间已缩窄，topK=10足矣
        boolean hasFilter = !filterParts.isEmpty();
        int topK = hasFilter ? TOP_K_FILTERED : TOP_K_UNFILTERED;
        SearchRequest.Builder builder = SearchRequest.builder()
                .topK(topK)
                .similarityThreshold(0.1);

        if (!filterParts.isEmpty()) {
            builder.filterExpression(String.join(" && ", filterParts));
        }

        List<Document> retrievedDocs = Collections.emptyList();
        try {
            retrievedDocs = vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            log.error("[Retrieval] 向量检索失败: {}", e.getMessage());
        }

        // 图片chunk诊断统计
        long imageChunkCount = retrievedDocs.stream()
                .filter(d -> "IMAGE".equalsIgnoreCase(
                        Objects.toString(d.getMetadata().get("chunk_type"), "")))
                .count();
        log.info("[Retrieval] 召回{}个chunk, 其中IMAGE类型{}个", retrievedDocs.size(), imageChunkCount);

        // 兜底：主检索未召回图片时，做一次独立的图片补充检索
        // 原因：图片描述文本向量相似度通常低于正文，topK=10时容易被挤掉
        if (imageChunkCount == 0 && !filterParts.isEmpty()) {
            List<Document> imageDocs = Collections.emptyList();
            
            // 方法1：尝试带 chunk_type 过滤的精确检索
            try {
                String imageFilter = String.join(" && ", filterParts)
                        + " && chunk_type == \"IMAGE\"";
                SearchRequest imageRequest = SearchRequest.builder()
                        .topK(10)
                        .similarityThreshold(0.01)
                        .filterExpression(imageFilter)
                        .build();
                imageDocs = vectorStore.similaritySearch(imageRequest);
            } catch (Exception e) {
                log.warn("[Retrieval-IMG] chunk_type过滤检索失败: {}", e.getMessage());
            }

            // 方法2：若精确过滤失败，用宽口径检索后Java侧过滤（兼容Milvus filter语法差异）
            if (imageDocs.isEmpty()) {
                try {
                    SearchRequest broadRequest = SearchRequest.builder()
                            .topK(30)
                            .similarityThreshold(0.01)
                            .filterExpression(String.join(" && ", filterParts))
                            .build();
                    List<Document> broadDocs = vectorStore.similaritySearch(broadRequest);
                    imageDocs = broadDocs.stream()
                            .filter(d -> "IMAGE".equalsIgnoreCase(
                                    Objects.toString(d.getMetadata().get("chunk_type"), "")))
                            .limit(5)
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    log.warn("[Retrieval-IMG] 宽口径检索也失败: {}", e.getMessage());
                }
            }

            if (!imageDocs.isEmpty()) {
                retrievedDocs = new ArrayList<>(retrievedDocs);
                retrievedDocs.addAll(imageDocs);
                log.info("[Retrieval-IMG] 补充检索到{}个图片chunk，合并后共{}个chunk",
                        imageDocs.size(), retrievedDocs.size());
            } else {
                log.info("[Retrieval-IMG] 补充检索未找到图片chunk（可能该知识库无图片文件）");
            }
        }

        // 诊断：如果带filter召回为0，尝试不带filter检索，判断是阈值还是filter导致
        if (retrievedDocs.isEmpty() && !filterParts.isEmpty()) {
            try {
                SearchRequest noFilterRequest = SearchRequest.builder()
                        .topK(5)
                        .similarityThreshold(0.1)
                        .build();
                List<Document> noFilterDocs = vectorStore.similaritySearch(noFilterRequest);
                log.warn("[Retrieval-Diag] 带filter召回0个，不带filter召回{}个 → filter表达式'{}'可能不匹配Milvus中实际数据",
                        noFilterDocs.size(), filterExpr);
                if (!noFilterDocs.isEmpty()) {
                    // 打印前3条的metadata字段，帮助排查filter字段名/类型问题
                    for (int i = 0; i < Math.min(3, noFilterDocs.size()); i++) {
                        log.warn("[Retrieval-Diag] chunk#{} metadata keys={}, kb_id={}, source={}",
                                i, noFilterDocs.get(i).getMetadata().keySet(),
                                noFilterDocs.get(i).getMetadata().get("kb_id"),
                                noFilterDocs.get(i).getMetadata().get("source"));
                    }
                }
            } catch (Exception e) {
                log.warn("[Retrieval-Diag] 无filter检索也失败: {}", e.getMessage());
            }
        }

        if (retrievedDocs.isEmpty()) {
            return Map.of(
                    StateKeys.DOCUMENTS, Collections.emptyList(),
                    StateKeys.STEPS, "检索完成: 未找到相关内容"
            );
        }

        // Small-to-Big: chunk → 父页面回表
        List<Document> parentDocs = mapToParentPages(retrievedDocs);

        log.info("[Retrieval] Small-to-Big: {}个chunk → {}个父页面",
                retrievedDocs.size(), parentDocs.size());

        // Rerank：无filter时做语义精排，筛掉向量假相关的文档
        if (!hasFilter && parentDocs.size() > 8) {
            String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
            Map<String, Object> rerankCtx = new HashMap<>();
            rerankCtx.put("query", question);
            rerankCtx.put("kbIds", kbIds);

            log.info("[Retrieval-Rerank] 无filter触发Rerank, 候选{}个页面", parentDocs.size());
            List<Document> rerankedDocs = null;

            // 优先使用 qwen3-rerank 专用模型（快、准、省）
            try {
                rerankedDocs = primaryRerank.rerank(parentDocs, rerankCtx);
            } catch (Exception e) {
                log.warn("[Retrieval-Rerank] qwen3-rerank失败，降级LLM Rerank: {}", e.getMessage());
            }

            // 降级：只有当 qwen3-rerank 完全失败（异常或0结果）时才走 LLM Rerank
            if (rerankedDocs == null || rerankedDocs.isEmpty()) {
                log.warn("[Retrieval-Rerank] qwen3-rerank返回空，降级LLM Rerank");
                try {
                    rerankedDocs = fallbackRerank.rerank(parentDocs, rerankCtx);
                } catch (Exception e) {
                    log.error("[Retrieval-Rerank] LLM Rerank也失败: {}", e.getMessage());
                }
            }

            // 救援机制：两层都失败或返回太少，用原始top 8兜底
            if (rerankedDocs == null || rerankedDocs.size() < 3) {
                log.warn("[Retrieval-Rerank] 两层Rerank均过滤过激(仅{}条)，降级原始top 8",
                        rerankedDocs == null ? 0 : rerankedDocs.size());
                parentDocs = parentDocs.subList(0, Math.min(8, parentDocs.size()));
            } else {
                parentDocs = rerankedDocs;
            }
            log.info("[Retrieval-Rerank] 最终保留{}个页面", parentDocs.size());
        }

        return Map.of(
                StateKeys.DOCUMENTS, parentDocs,
                StateKeys.STEPS, "检索完成: 命中" + parentDocs.size() + "个相关页面"
        );
    }

    /**
     * Small-to-Big: chunk页面指针 → MySQL回表查原文
     * <p>复刻 RAG-Challenge-2 的 return_parent_pages 模式</p>
     * <p>修复：IMAGE/TABLE等非TEXT类型的chunk，其页面不在document_pages表中，
     * 不应被静默丢弃，而应保留原始chunk内容（图片描述、表格文本等）</p>
     */
    private List<Document> mapToParentPages(List<Document> chunks) {
        Map<String, List<Integer>> svPagesMap = new LinkedHashMap<>();
        // 记录已成功回表的(source, page)组合，用于后续判断哪些chunk需要兜底保留
        Set<String> resolvedPageKeys = new HashSet<>();

        for (Document chunk : chunks) {
            Object sourceObj = chunk.getMetadata().get("source");
            Object versionObj = chunk.getMetadata().get("version");
            Object pageObj = chunk.getMetadata().get("page");
            if (sourceObj == null || pageObj == null) continue;

            String svKey = sourceObj + "|" + (versionObj != null ? versionObj : "");
            int page = pageObj instanceof Integer ? (Integer) pageObj
                    : (int) Double.parseDouble(pageObj.toString());
            svPagesMap.computeIfAbsent(svKey, k -> new ArrayList<>()).add(page);
        }

        Map<String, Document> pageMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : svPagesMap.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String source = parts[0];
            String version = parts[1];
            List<Integer> pageNums = new ArrayList<>(new LinkedHashSet<>(entry.getValue()));

            Map<String, String> pageTexts = documentPageService.getPageTexts(source, version, pageNums);

            for (Integer pn : pageNums) {
                String key = source + "_" + pn;
                if (pageMap.containsKey(key)) continue;
                String fullText = pageTexts.get(key);
                if (fullText == null || fullText.isEmpty()) continue;

                resolvedPageKeys.add(key);

                Map<String, Object> meta = new HashMap<>();
                meta.put("source", source);
                meta.put("page", pn);
                meta.put("version", version);
                meta.put("chunk_type", "PARENT_PAGE");
                for (Document chunk : chunks) {
                    if (source.equals(chunk.getMetadata().get("source"))
                            && pn.equals(chunk.getMetadata().get("page"))) {
                        if (chunk.getMetadata().get("kb_id") != null)
                            meta.put("kb_id", chunk.getMetadata().get("kb_id"));
                        if (chunk.getMetadata().get("kb_name") != null)
                            meta.put("kb_name", chunk.getMetadata().get("kb_name"));
                        break;
                    }
                }

                pageMap.put(key, new Document(truncatePageText(fullText), meta));
            }
        }

        // 兜底：保留所有未被回表的原始chunk（IMAGE/TABLE类型在document_pages中无记录，不应丢弃）
        for (Document chunk : chunks) {
            Object pageObj = chunk.getMetadata().get("page");
            Object sourceObj = chunk.getMetadata().get("source");
            if (pageObj == null) {
                // 无页码的chunk直接保留
                pageMap.putIfAbsent("nopage_" + chunk.hashCode(), chunk);
            } else if (sourceObj != null) {
                // 有页码但未能回表的chunk（如IMAGE/TABLE）：保留原始内容
                String key = sourceObj + "_" + pageObj;
                if (!resolvedPageKeys.contains(key)) {
                    String fallbackKey = "raw_" + sourceObj + "_" + pageObj + "_" + chunk.hashCode();
                    pageMap.putIfAbsent(fallbackKey, chunk);
                }
            }
        }

        return new ArrayList<>(pageMap.values());
    }

    private String truncatePageText(String text) {
        final int MAX = 8000;
        if (text == null || text.length() <= MAX) return text != null ? text : "";
        String truncated = text.substring(0, MAX);
        int lastBreak = truncated.lastIndexOf("\n\n");
        if (lastBreak > MAX / 2) truncated = truncated.substring(0, lastBreak);
        return truncated + "\n（内容已截断）";
    }
}
