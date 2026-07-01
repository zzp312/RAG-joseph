package com.xushu.rag.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.service.IRerankStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM语义相关性重排序策略
 * <p>参照RAG-Challenge-2冠军方案：粗召回30条 → LLM批量打分 → 加权融合 → 取top 8</p>
 * <p>
 * 融合公式：combinedScore = vectorScore × 0.3 + llmScore × 0.7
 * 阈值：combinedScore ≥ 0.4
 * 批量：每3页为一批调用LLM，降低API调用次数
 * </p>
 * <p>仅无filter时触发（有KB/文件过滤时搜索空间已缩窄，Rerank收益递减）</p>
 *
 * @author Joseph
 */
@Slf4j
@Component("llmRerankStrategy")
public class LLMRerankStrategy implements IRerankStrategy {

    /** 向量分数权重 */
    private static final double VECTOR_WEIGHT = 0.3;
    /** LLM相关性分数权重 */
    private static final double LLM_WEIGHT = 0.7;
    /** 最低融合分数阈值 */
    private static final double MIN_COMBINED_SCORE = 0.4;
    /** 每次LLM打分批处理的页面数 */
    private static final int BATCH_SIZE = 3;
    /** 最终保留的最大页面数 */
    private static final int MAX_RESULTS = 8;
    /** 页面文本截断长度（降低Token成本） */
    private static final int PAGE_TRUNCATE_LENGTH = 2000;

    private final ChatClient chatClient;

    private static final String RERANK_PROMPT = """
            你是一个文档相关性评估器。评估以下页面内容与用户问题的相关程度。
            
            用户问题：%s
            
            页面内容：
            %s
            
            评分标准（0到1之间，保留1位小数）：
            - 0.8~1.0：页面直接包含问题答案或密切相关
            - 0.5~0.7：页面的主题/领域与问题相关，但截取部分未直接涉及问题
            - 0.2~0.4：页面主题与问题有一些关联
            - 0.0~0.1：完全无关
            
            注意：页面可能很长，仅展示了前段内容。如果文档主题（如文件名、领域）与问题匹配，即使截取部分未直接回答，也应给出中等分数(0.5~0.7)。
            
            请严格按JSON格式输出，仅包含score字段：
            {"score": 0.0}
            """;

    public LLMRerankStrategy(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public List<Document> rerank(List<Document> documents, Map<String, Object> context) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        String query = (String) context.getOrDefault("query", "");

        // 少于等于MAX_RESULTS条时无需Rerank，直接返回
        if (documents.size() <= MAX_RESULTS) {
            log.info("[LLMRerank] 文档数{}≤{}，跳过Rerank直接返回",
                    documents.size(), MAX_RESULTS);
            return documents;
        }

        log.info("[LLMRerank] 开始Rerank, 文档数={}, query={}",
                documents.size(), query.length() > 40
                        ? query.substring(0, 40) + "..." : query);

        // 分批调用LLM打分
        Map<Integer, Double> llmScores = new HashMap<>();
        long startTime = System.currentTimeMillis();
        int batchCount = 0;

        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            int endIdx = Math.min(i + BATCH_SIZE, documents.size());
            StringBuilder batchText = new StringBuilder();
            for (int j = i; j < endIdx; j++) {
                String source = Objects.toString(
                        documents.get(j).getMetadata().get("source"), "未知");
                String text = documents.get(j).getText();
                String truncated = text.length() > PAGE_TRUNCATE_LENGTH
                        ? text.substring(0, PAGE_TRUNCATE_LENGTH) + "..."
                        : text;
                batchText.append("--- 页面 ").append(j - i + 1)
                        .append(" (").append(source).append(") ---\n")
                        .append(truncated).append("\n\n");
            }

            Map<Integer, Double> batchScores = scoreBatch(query, batchText.toString());
            // 将批次内索引映射回文档全局索引
            for (Map.Entry<Integer, Double> entry : batchScores.entrySet()) {
                llmScores.put(i + entry.getKey(), entry.getValue());
            }
            batchCount++;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[LLMRerank] 打分完成: {}批, {}ms, 平均每批{}ms",
                batchCount, elapsed, batchCount > 0 ? elapsed / batchCount : 0);

        // 加权融合：vectorScore × 0.3 + llmScore × 0.7
        // 先收集所有文档的分数，无论是否通过阈值，用于诊断
        List<Document> ranked = new ArrayList<>();
        List<double[]> allScores = new ArrayList<>(); // [index, vector, llm, combined, source]

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            double vectorScore = extractVectorScore(doc);
            Double llmScore = llmScores.getOrDefault(i, 0.0);
            double combined = vectorScore * VECTOR_WEIGHT + llmScore * LLM_WEIGHT;

            allScores.add(new double[]{i, vectorScore, llmScore, combined});

            if (combined >= MIN_COMBINED_SCORE) {
                // 将融合分数写入metadata，便于调试
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("_rerank_vector", Math.round(vectorScore * 1000.0) / 1000.0);
                meta.put("_rerank_llm", Math.round(llmScore * 1000.0) / 1000.0);
                meta.put("_rerank_combined", Math.round(combined * 1000.0) / 1000.0);
                ranked.add(new Document(doc.getText(), meta));
            }
        }

        // 诊断：打印top 3分数（含未通过阈值的）
        allScores.sort((a, b) -> Double.compare(b[3], a[3]));
        for (int i = 0; i < Math.min(3, allScores.size()); i++) {
            double[] s = allScores.get(i);
            Document d = documents.get((int) s[0]);
            String src = Objects.toString(d.getMetadata().get("source"), "?");
            if (src.length() > 40) src = src.substring(0, 40) + "...";
            log.info("[LLMRerank-score] #{} {}: vector={}, llm={}, combined={} {}",
                    i + 1, src, Math.round(s[1] * 100.0) / 100.0,
                    Math.round(s[2] * 100.0) / 100.0,
                    Math.round(s[3] * 100.0) / 100.0,
                    s[3] >= MIN_COMBINED_SCORE ? "✓" : "✗");
        }

        // 按融合分数降序排列
        ranked.sort((a, b) -> Double.compare(
                (Double) b.getMetadata().getOrDefault("_rerank_combined", 0.0),
                (Double) a.getMetadata().getOrDefault("_rerank_combined", 0.0)));

        // 截取top N
        List<Document> result = ranked.subList(0, Math.min(MAX_RESULTS, ranked.size()));

        log.info("[LLMRerank] Rerank完成: {}个文档 → {}个通过(≥{}) → 取top {}个",
                documents.size(), ranked.size(), MIN_COMBINED_SCORE, result.size());

        return result;
    }

    /**
     * 对一批页面调用LLM进行相关性打分
     *
     * @param query     用户问题
     * @param batchText 批量页面文本
     * @return 页面在批次内的索引 → 相关性分数
     */
    private Map<Integer, Double> scoreBatch(String query, String batchText) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        try {
            String prompt = String.format(RERANK_PROMPT, query, batchText);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.trim().isEmpty()) {
                log.warn("[LLMRerank] LLM返回空，该批次默认0分");
                return scores;
            }

            // 解析JSON: {"score": 0.7} 或 {"scores": [0.7, 0.3, 0.9]} 或 [{"score":0.7}, ...]
            parseScores(response, scores);
        } catch (Exception e) {
            log.error("[LLMRerank] LLM打分异常: {}", e.getMessage());
        }
        return scores;
    }

    /**
     * 解析LLM返回的打分结果，兼容多种JSON格式
     */
    private void parseScores(String response, Map<Integer, Double> scores) {
        // 尝试数组格式: [{"score":0.7}, {"score":0.3}]
        try {
            if (response.trim().startsWith("[")) {
                com.alibaba.fastjson.JSONArray arr = JSON.parseArray(response);
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (item.containsKey("score")) {
                        scores.put(i, clipScore(item.getDoubleValue("score")));
                    }
                }
                return;
            }
        } catch (Exception ignored) {
        }

        // 尝试单对象格式: {"score": 0.7}
        try {
            if (response.trim().startsWith("{")) {
                JSONObject item = JSON.parseObject(response);
                if (item.containsKey("score")) {
                    scores.put(0, clipScore(item.getDoubleValue("score")));
                    return;
                }
            }
        } catch (Exception ignored) {
        }

        // 尝试提取数字
        try {
            String cleaned = response.replaceAll("[^0-9.]", " ").trim();
            String[] parts = cleaned.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    double val = Double.parseDouble(parts[i]);
                    if (val >= 0 && val <= 1) {
                        scores.put(i, clipScore(val));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[LLMRerank] 无法解析打分结果: {}", response);
        }
    }

    /**
     * 从文档metadata中提取Milvus相似度分数
     */
    private double extractVectorScore(Document doc) {
        Object scoreObj = doc.getMetadata().getOrDefault("_distance", null);
        if (scoreObj != null) {
            try {
                double dist = Double.parseDouble(scoreObj.toString());
                // Milvus返回的是距离，转换为相似度（余弦距离 → 相似度 = 1 - 距离）
                return Math.max(0.0, Math.min(1.0, 1.0 - dist));
            } catch (NumberFormatException ignored) {
            }
        }
        // 无分数时给默认值
        return 0.5;
    }

    /**
     * 将分数限制在0~1范围内并保留1位小数
     */
    private double clipScore(double score) {
        return Math.round(Math.max(0.0, Math.min(1.0, score)) * 10.0) / 10.0;
    }
}
