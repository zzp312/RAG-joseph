package com.xushu.rag.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.service.IRerankStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DashScope qwen3-rerank 专用重排序策略
 * <p>调用阿里云百炼 Text Rerank API，一次请求完成所有文档打分，
 * 速度快（~0.5s）、准确性高（专门训练的Cross-Encoder）、成本低</p>
 * <p>
 * LLMRerankStrategy 作为降级兜底，本策略失败时自动回退
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component("qwen3RerankStrategy")
public class Qwen3RerankStrategy implements IRerankStrategy {

    /** 最低相关性分数阈值 */
    private static final double MIN_SCORE = 0.3;
    /** 最终保留的最大页面数 */
    private static final int MAX_RESULTS = 8;
    /** 每页截断字符数（API限制30720总输入，按最多20页留余量：30720/20≈1500） */
    private static final int PAGE_TRUNCATE_CHARS = 1500;
    /** API 总输入上限 */
    private static final int API_MAX_TOTAL_CHARS = 30720;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${spring.ai.dashscope.rerank.model:qwen3-rerank}")
    private String model;

    @Value("${spring.ai.dashscope.rerank.base-url}")
    private String baseUrl;

    @Override
    public List<Document> rerank(List<Document> documents, Map<String, Object> context) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        String query = (String) context.getOrDefault("query", "");

        if (documents.size() <= MAX_RESULTS) {
            log.info("[Qwen3Rerank] 文档数{}≤{}，跳过Rerank", documents.size(), MAX_RESULTS);
            return documents;
        }

        log.info("[Qwen3Rerank] 开始Rerank, 文档数={}, query={}",
                documents.size(), query.length() > 40
                        ? query.substring(0, 40) + "..." : query);

        long startTime = System.currentTimeMillis();

        // 构建请求：动态截断（根据文档数分配额度，避免硬截断浪费额度）
        int maxPerDoc = Math.min(PAGE_TRUNCATE_CHARS, API_MAX_TOTAL_CHARS / Math.max(1, documents.size()));
        log.debug("[Qwen3Rerank] 动态截断: {}篇文档, 每篇上限{}字", documents.size(), maxPerDoc);
        List<String> docTexts = documents.stream()
                .map(d -> {
                    String text = d.getText();
                    if (text == null || text.isBlank()) {
                        return "-"; // 占位，避免空文本导致API报错
                    }
                    return text.length() > maxPerDoc
                            ? text.substring(0, maxPerDoc)
                            : text;
                })
                .collect(Collectors.toList());

        Map<Integer, Double> scores;
        try {
            scores = callRerankApi(query, docTexts);
        } catch (Exception e) {
            log.error("[Qwen3Rerank] API调用失败: {}", e.getMessage());
            throw new RuntimeException("Qwen3Rerank failed", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Qwen3Rerank] 打分完成: {}ms, 返回{}个分数",
                elapsed, scores.size());

        // 按分数过滤+排序
        List<Document> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            int idx = entry.getKey();
            double score = entry.getValue();
            if (score >= MIN_SCORE && idx < documents.size()) {
                Document doc = documents.get(idx);
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("_rerank_score", Math.round(score * 1000.0) / 1000.0);
                meta.put("_rerank_type", "qwen3");
                ranked.add(new Document(doc.getText(), meta));
            }
        }

        // 打印top 3诊断
        List<Map.Entry<Integer, Double>> sortedScores = new ArrayList<>(scores.entrySet());
        sortedScores.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(3, sortedScores.size()); i++) {
            int idx = sortedScores.get(i).getKey();
            String src = Objects.toString(
                    idx < documents.size()
                            ? documents.get(idx).getMetadata().get("source") : "?", "?");
            if (src.length() > 40) src = src.substring(0, 40) + "...";
            double s = sortedScores.get(i).getValue();
            log.info("[Qwen3Rerank-score] #{} {}: score={} {}",
                    i + 1, src, Math.round(s * 100.0) / 100.0,
                    s >= MIN_SCORE ? "✓" : "✗");
        }

        List<Document> result = ranked.subList(0, Math.min(MAX_RESULTS, ranked.size()));
        log.info("[Qwen3Rerank] Rerank完成: {}个通过(≥{}) → 取top {}个",
                ranked.size(), MIN_SCORE, result.size());

        return result;
    }

    /**
     * 调用 DashScope qwen3-rerank API
     */
    private Map<Integer, Double> callRerankApi(String query, List<String> documents) throws IOException {
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("query", query);
        request.put("documents", documents);
        request.put("top_n", MAX_RESULTS);

        String body = request.toJSONString();
        log.debug("[Qwen3Rerank] 请求: query={}字, docs={}个, body={}字节",
                query.length(), documents.size(), body.getBytes(StandardCharsets.UTF_8).length);

        URL url = new URL(baseUrl);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300
                ? conn.getInputStream() : conn.getErrorStream();

        String response;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            response = br.lines().collect(Collectors.joining("\n"));
        }
        conn.disconnect();

        if (status != 200) {
            log.error("[Qwen3Rerank] API返回{}: {}", status, response);
            throw new IOException("Rerank API returned " + status + ": " + response);
        }

        return parseResponse(response);
    }

    /**
     * 解析API响应：{"results":[{"index":0,"relevance_score":0.93},...]}
     */
    private Map<Integer, Double> parseResponse(String response) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        JSONObject resp = JSON.parseObject(response);
        JSONArray results = resp.getJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                int index = item.getIntValue("index");
                double score = item.getDoubleValue("relevance_score");
                scores.put(index, score);
            }
        }
        return scores;
    }
}
