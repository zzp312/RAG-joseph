package com.xushu.rag.graph.nodes;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 查询拆解Node（对标冠军方案 Query Router）
 * <p>对 COMPARISON / AGGREGATION 意图，LLM 拆为多个子问题，
 * 后续由 Graph 并行执行检索。非拆解意图直接透传。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class QueryDecomposeNode {

    private final ChatClient chatClient;

    public QueryDecomposeNode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, Object> state) {
        String category = (String) state.getOrDefault(StateKeys.CATEGORY, "unknown");
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");

        // 只对 COMPARISON / AGGREGATION 拆解
        if (!"comparison".equalsIgnoreCase(category) && !"aggregation".equalsIgnoreCase(category)) {
            log.info("[Decompose] category={}, 不拆解, 透传", category);
            return Map.of(StateKeys.STEPS, "查询无需拆解");
        }

        List<String> subQueries = decompose(question);
        if (subQueries == null || subQueries.size() <= 1) {
            log.info("[Decompose] category={}, 拆解失败或仅1个子问题, 透传", category);
            return Map.of(StateKeys.STEPS, "查询拆解: 仅1个子问题");
        }

        log.info("[Decompose] category={}, 拆出{}个子问题: {}", category, subQueries.size(), subQueries);

        // 将子问题存入 State（后续节点可读取并行检索）
        Map<String, Object> result = new HashMap<>();
        result.put("subQueries", subQueries);
        result.put(StateKeys.STEPS, "查询拆解为" + subQueries.size() + "个子问题");

        // 用第一个子问题作为主检索问句（Graph 工作流仍旧单路走完，
        // 后续迭代可改为多路并行检索合并）
        result.put(StateKeys.QUESTION, String.join(" | ", subQueries));

        return result;
    }

    private List<String> decompose(String question) {
        String prompt = """
            将以下复杂问题拆解为2~4个独立的简单子问题，每个子问题可以独立检索回答。
            以JSON数组格式返回，只返回数组不要其他内容。
            示例输入："SVM和决策树有什么区别"
            示例输出：["SVM支持向量机详细介绍","决策树详细介绍"]

            问题：%s
            """.formatted(question);

        try {
            String resp = chatClient.prompt().user(prompt).call().content();
            if (resp == null) return Collections.emptyList();
            resp = resp.trim();

            // 尝试提取 JSON 数组
            int start = resp.indexOf('[');
            int end = resp.lastIndexOf(']');
            if (start >= 0 && end > start) {
                resp = resp.substring(start, end + 1);
            }
            return JSON.parseArray(resp, String.class);
        } catch (Exception e) {
            log.warn("[Decompose] 拆解失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
