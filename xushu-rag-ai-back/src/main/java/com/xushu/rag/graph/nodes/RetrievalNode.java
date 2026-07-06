package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.service.DocumentPageService;
import com.xushu.rag.service.HybridSearchService;
import com.xushu.rag.service.IRerankStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量检索Node（Modality-Aware Reranking 架构）
 * <p>完整链路：Milvus混合检索 → VersionFirst排序 → Small-to-Big回表
 * → 模态分离（TEXT/IMAGE/TABLE） → 文本Rerank(主Rerank) + 图片关联检索
 * → 图片独立Rerank(top-{maxImages}) → 最终合并</p>
 * <p>核心设计：不同模态在不同语义空间，混在一起Rerank会导致图片/表格被文本淹没。
 * 参照业界Multi-modal RAG的Modality-Aware Reranking模式，各模态独立精排。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class RetrievalNode {

    /** 一阶段粗召回 topK（有 Qwen3Rerank 精排兜底，可放宽门槛捕获更多候选） */
    private static final int TOP_K = 20;

    /** Qwen3Rerank 文本精排后保留的最大文本/父页面文档数 */
    private static final int MAX_TEXT_DOCS = 8;

    /** 图片独立 Rerank 后保留的最大图片数（可配置） */
    @Value("${retrieval.image.max-count:3}")
    private int maxImages;

    private final HybridSearchService hybridSearchService;
    private final DocumentPageService documentPageService;
    private final IRerankStrategy versionFirstRerank;
    /** 保留注入（暂不使用，待P1/P2恢复语义Rerank） */
    private final IRerankStrategy primaryRerank;
    private final IRerankStrategy fallbackRerank;

    public RetrievalNode(HybridSearchService hybridSearchService,
                         DocumentPageService documentPageService,
                         @Qualifier("versionFirstRerankStrategy") IRerankStrategy versionFirstRerank,
                         @Qualifier("qwen3RerankStrategy") IRerankStrategy primaryRerank,
                         @Qualifier("llmRerankStrategy") IRerankStrategy fallbackRerank) {
        this.hybridSearchService = hybridSearchService;
        this.documentPageService = documentPageService;
        this.versionFirstRerank = versionFirstRerank;
        this.primaryRerank = primaryRerank;
        this.fallbackRerank = fallbackRerank;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        @SuppressWarnings("unchecked")
        List<Long> kbIds = (List<Long>) state.getOrDefault(StateKeys.KB_IDS, null);
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) state.getOrDefault(StateKeys.SOURCES, null);

        log.info("[Retrieval-Diag] stateKeys={}, question={}",
                state.keySet(),
                ((String) state.getOrDefault(StateKeys.QUESTION, "")).length() > 30
                    ? ((String) state.get(StateKeys.QUESTION)).substring(0, 30) + "..."
                    : state.get(StateKeys.QUESTION));

        // 构建过滤条件（与旧版 AiRagController 完全一致）
        List<String> filterParts = new ArrayList<>();
        if (sources != null && !sources.isEmpty()) {
            filterParts.add("source in " + com.alibaba.fastjson.JSON.toJSONString(sources));
        } else if (kbIds != null && !kbIds.isEmpty()) {
            filterParts.add("kb_id in " + com.alibaba.fastjson.JSON.toJSONString(kbIds));
        }

        String filterLog = filterParts.isEmpty() ? "无过滤(全文件)" : String.join(" && ", filterParts);
        log.info("[Retrieval] kbIds={}, sources={}, filter={}", kbIds, sources, filterLog);

        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");

        // ① 混合检索：Dense + BM25（BM25不可用时自动降级为纯向量检索）
        String filterExpr = filterParts.isEmpty() ? null : String.join(" && ", filterParts);
        List<Document> retrievedDocs = Collections.emptyList();
        try {
            retrievedDocs = hybridSearchService.search(question, TOP_K, filterExpr);
        } catch (Exception e) {
            log.error("[Retrieval] ①混合检索失败: {}", e.getMessage());
        }
        log.info("[Retrieval] ①Milvus检索 → {}个chunk", retrievedDocs.size());

        if (retrievedDocs.isEmpty()) {
            return Map.of(
                    StateKeys.DOCUMENTS, Collections.emptyList(),
                    StateKeys.STEPS, "检索完成: 未找到相关内容"
            );
        }

        // 留存原始chunk副本（Small-to-Big后需要回补，含IMAGE描述等非TEXT内容）
        List<Document> rawChunks = new ArrayList<>(retrievedDocs);

        // ② VersionFirst排序（对标旧版 rerankStrategy.rerank()，不筛不丢，只排序）
        Map<String, Object> versionFirstCtx = new HashMap<>();
        versionFirstCtx.put("query", question);
        versionFirstCtx.put("kbIds", kbIds);
        List<Document> rankedDocs = versionFirstRerank.rerank(retrievedDocs, versionFirstCtx);
        log.info("[Retrieval] ②VersionFirst → {}个chunk（不过滤）", rankedDocs.size());

        // ③ Small-to-Big: chunk → 父页面回表（对标旧版 mapToParentPages）
        List<Document> parentDocs = mapToParentPages(rankedDocs);
        log.info("[Retrieval] ③Small-to-Big: {}chunk→{}父页面", rankedDocs.size(), parentDocs.size());

        // ④ 合并父页面+原始chunk，同时按模态分离（TEXT/PARENT_PAGE vs IMAGE vs TABLE）
        List<Document> textDocs = new ArrayList<>(parentDocs);  // PARENT_PAGE → 文本
        List<Document> imageDocs = new ArrayList<>();
        List<Document> tableDocs = new ArrayList<>();
        Set<String> seenTexts = parentDocs.stream()
                .map(Document::getText)
                .collect(Collectors.toSet());
        for (Document raw : rawChunks) {
            if (!seenTexts.add(raw.getText())) continue;
            String chunkType = Objects.toString(raw.getMetadata().get("chunk_type"), "").toUpperCase();
            switch (chunkType) {
                case "IMAGE" -> imageDocs.add(raw);
                case "TABLE" -> tableDocs.add(raw);
                default -> textDocs.add(raw);
            }
        }
        log.info("[Retrieval] ④模态分离: TEXT={}个, IMAGE={}个, TABLE={}个",
                textDocs.size(), imageDocs.size(), tableDocs.size());

        // ⑤ 文本Rerank：仅对 TEXT/PARENT_PAGE 做主Rerank（图像/表格不参与，避免被文本淹没）
        Map<String, Object> rerankCtx = new HashMap<>();
        rerankCtx.put("query", question);
        rerankCtx.put("kbIds", kbIds);
        if (textDocs.size() > MAX_TEXT_DOCS) {
            log.info("[Retrieval] ⑤文本Rerank 触发, 候选{}个文本文档", textDocs.size());
            textDocs = rerankWithFallback(textDocs, rerankCtx);
            log.info("[Retrieval] ⑤文本Rerank完成 → {}个文本文档", textDocs.size());
        }

        // ⑥ 图片关联检索：按已命中文本的 source+version 拉取同文档所有图片
        Set<String> svPairs = new LinkedHashSet<>();
        for (Document doc : textDocs) {
            String source = Objects.toString(doc.getMetadata().get("source"), "");
            String version = Objects.toString(doc.getMetadata().get("version"), "");
            if (!source.isEmpty()) {
                svPairs.add(source + "|" + version);
            }
        }
        if (!svPairs.isEmpty()) {
            List<Document> associatedImages = hybridSearchService.fetchImagesBySourceVersion(svPairs);
            Set<String> existingIds = imageDocs.stream().map(Document::getId).collect(Collectors.toSet());
            int added = 0;
            for (Document img : associatedImages) {
                if (existingIds.add(img.getId())) {
                    imageDocs.add(img);
                    added++;
                }
            }
            if (added > 0) {
                log.info("[Retrieval] ⑥图片关联: {}个(source,version) → 新增{}张图片",
                        svPairs.size(), added);
            }
        }

        // ⑦ 图片独立Rerank：与文本分离，图片描述 vs query 独立打分，超过阈值则取 top-N
        if (imageDocs.size() > maxImages) {
            log.info("[Retrieval] ⑦图片Rerank 触发, 候选{}张图片, 阈值maxImages={}",
                    imageDocs.size(), maxImages);
            Map<String, Object> imgRerankCtx = new HashMap<>(rerankCtx);
            imgRerankCtx.put("maxResults", maxImages);
            imageDocs = rerankImages(imageDocs, imgRerankCtx, maxImages);
            log.info("[Retrieval] ⑦图片Rerank完成 → top{}张图片", imageDocs.size());
        }

        // ⑧ 最终合并：文本 + 精排图片 + 表格
        List<Document> merged = new ArrayList<>(textDocs);
        merged.addAll(imageDocs);
        merged.addAll(tableDocs);

        long finalImgCount = imageDocs.size();
        long finalTblCount = tableDocs.size();
        log.info("[Retrieval] 最终输出: {}个页面 (TEXT={}, IMAGE={}, TABLE={})",
                merged.size(), textDocs.size(), finalImgCount, finalTblCount);

        return Map.of(
                StateKeys.DOCUMENTS, merged,
                StateKeys.STEPS, "检索完成: 命中" + merged.size() + "个相关页面"
        );
    }

    /**
     * 图片独立 Rerank：与文本池分离，图片描述(embedding标签+视觉描述) vs query 独立打分
     * <p>设计理念：图片的语义密度远低于文本（短标签 vs 长篇段落），
     * 混在同一个 Rerank 池中会被文本一致性淹没。独立打分后取 top-N。</p>
     *
     * @param images   图片文档列表（desc 文本可用于语义打分）
     * @param ctx      上下文（query 等）
     * @param maxCount 保留的最大图片数
     * @return top-N 图片文档
     */
    private List<Document> rerankImages(List<Document> images, Map<String, Object> ctx, int maxCount) {
        if (images.size() <= maxCount) return images;

        try {
            List<Document> ranked = primaryRerank.rerank(images, ctx);
            if (ranked != null && !ranked.isEmpty()) {
                List<Document> result = ranked.subList(0, Math.min(maxCount, ranked.size()));
                log.info("[Rerank-img] Qwen3Rerank返回{}张, 截取top{}张", ranked.size(), result.size());
                return result;
            }
            // ranked 非 null 但为空 → 所有图片分数都低于阈值，不相关
            log.info("[Rerank-img] Qwen3Rerank返回0张(全部低于分数阈值)，舍弃所有图片");
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[Rerank-img] Qwen3Rerank异常, 降级取首个: {}", e.getMessage());
            // 只在 API 故障时降级，不因低相关度降级
            return images.subList(0, Math.min(1, images.size()));
        }
    }

    /**
     * Rerank + LLM降级 + 救援机制（标准三层容灾）
     */
    private List<Document> rerankWithFallback(List<Document> docs, Map<String, Object> ctx) {
        List<Document> result = null;
        try {
            result = primaryRerank.rerank(docs, ctx);
        } catch (Exception e) {
            log.warn("[Rerank] Qwen3Rerank失败, 降级LLM: {}", e.getMessage());
        }
        if (result == null || result.isEmpty()) {
            try {
                result = fallbackRerank.rerank(docs, ctx);
            } catch (Exception e) {
                log.error("[Rerank] LLM降级也失败: {}", e.getMessage());
            }
        }
        if (result == null || result.size() < 3) {
            log.warn("[Rerank] 两层均过滤过激(仅{}条), 降级原始top8",
                    result == null ? 0 : result.size());
            return docs.subList(0, Math.min(8, docs.size()));
        }
        return result;
    }

    /**
     * Small-to-Big: chunk页面指针 → MySQL回表查原文
     * <p>完全对标旧版 AiRagController.mapToParentPages()，不做特殊IMAGE/TABLE处理</p>
     *
     * @author Joseph
     */
    private List<Document> mapToParentPages(List<Document> chunks) {
        // 1. 收集所有(source+version, page)组合
        Map<String, List<Integer>> svPagesMap = new LinkedHashMap<>();
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

        // 2. 按(source,version)回表MySQL查原文
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

                Map<String, Object> pageMeta = new HashMap<>();
                pageMeta.put("source", source);
                pageMeta.put("page", pn);
                pageMeta.put("version", version);
                pageMeta.put("chunk_type", "PARENT_PAGE");
                for (Document chunk : chunks) {
                    if (source.equals(chunk.getMetadata().get("source"))
                            && pn.equals(chunk.getMetadata().get("page"))) {
                        if (chunk.getMetadata().get("kb_id") != null)
                            pageMeta.put("kb_id", chunk.getMetadata().get("kb_id"));
                        if (chunk.getMetadata().get("kb_name") != null)
                            pageMeta.put("kb_name", chunk.getMetadata().get("kb_name"));
                        break;
                    }
                }
                pageMap.put(key, new Document(truncateText(fullText), pageMeta));
            }
        }

        // 3. 无页码的chunk直接保留（对标旧版最后一步）
        for (Document chunk : chunks) {
            if (chunk.getMetadata().get("page") == null) {
                pageMap.putIfAbsent("nopage_" + chunk.hashCode(), chunk);
            }
        }

        return new ArrayList<>(pageMap.values());
    }

    private String truncateText(String text) {
        final int MAX = 8000;
        if (text == null || text.length() <= MAX) return text != null ? text : "";
        String truncated = text.substring(0, MAX);
        int lastBreak = truncated.lastIndexOf("\n\n");
        if (lastBreak > MAX / 2) truncated = truncated.substring(0, lastBreak);
        return truncated + "\n（内容已截断）";
    }
}
