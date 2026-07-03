package com.xushu.rag.graph.nodes;

import com.xushu.rag.classifier.IntentClassifier;
import com.xushu.rag.classifier.KeywordIntentClassifier;
import com.xushu.rag.classifier.LLMIntentClassifier;
import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 意图分类Node（两层漏斗：L1关键词 + L2 LLM）
 * <p>
 * L1关键词：0ms延迟，0 token，覆盖高频问候/指示词
 * L2 LLM：~200 token，处理长尾请求
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class IntentClassifyNode {

    /** 对话记忆保留消息数（5轮 = 10条消息，与 LLMGenerateNode 对齐） */
    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;

    private final KeywordIntentClassifier keywordClassifier;
    private final LLMIntentClassifier llmClassifier;
    private final ChatMemory chatMemory;

    public IntentClassifyNode(KeywordIntentClassifier keywordClassifier,
                              LLMIntentClassifier llmClassifier,
                              ChatMemory chatMemory) {
        this.keywordClassifier = keywordClassifier;
        this.llmClassifier = llmClassifier;
        this.chatMemory = chatMemory;
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        String conversationId = (String) state.getOrDefault(StateKeys.CONVERSATION_ID, "default");
        if (question.trim().isEmpty()) {
            return Map.of(StateKeys.CATEGORY, "chitchat");
        }

        // 读取对话历史（用于上下文判断 toolConfirm）
        List<Message> historyMessages = chatMemory.get(conversationId);
        // 只取最近 N 条消息
        if (historyMessages != null && historyMessages.size() > CHAT_MEMORY_RESPONSE_SIZE) {
            historyMessages = historyMessages.subList(
                    historyMessages.size() - CHAT_MEMORY_RESPONSE_SIZE,
                    historyMessages.size());
        }
        String historyText = formatHistory(historyMessages);

        // L1: 关键词快速路由（0 token）
        IntentClassifier.ClassifyResult result = keywordClassifier.classify(question);
        if (result == null) {
            // L2: LLM兜底（~200 token，带对话历史）
            result = llmClassifier.classify(question, historyText);
        }

        String category = result.getCategory().getValue();

        // 覆写：检测对比/聚合语义（QueryDecomposeNode 触发条件）
        String q = question.toLowerCase();
        if (q.contains("区别") || q.contains("对比") || q.contains("比较") || q.contains("哪个更好")
                || q.contains("优缺点") || q.contains("异同") || q.contains("差异")) {
            category = "comparison";
        } else if (q.contains("汇总") || q.contains("统计") || q.contains("最多") || q.contains("最少")
                || q.contains("列出所有") || q.contains("有哪些")) {
            category = "aggregation";
        }

        log.info("[IntentClassify] question='{}', category={}, layer={}, tokenUsed={}, emotion={}, toolConfirm={}, targetMcpServer={}, historyMsg={}",
                question.length() > 40 ? question.substring(0, 40) + "..." : question,
                category, result.getLayer(), result.getTokenUsed(), result.getEmotion(),
                result.isToolConfirm(), result.getTargetMcpServer(), historyMessages.size());

        Map<String, Object> resultMap = new java.util.HashMap<>();
        resultMap.put(StateKeys.CATEGORY, category);
        resultMap.put(StateKeys.EMOTION, result.getEmotion());
        resultMap.put(StateKeys.TOOL_CONFIRM, result.isToolConfirm());
        resultMap.put(StateKeys.STEPS, "意图分类完成: " + category + " (" + result.getLayer() + ")");
        // 写入目标MCP服务名（由LLM识别，用于McpToolCallNode精准调用）
        if (result.getTargetMcpServer() != null && !result.getTargetMcpServer().isEmpty()) {
            resultMap.put(StateKeys.TARGET_MCP_SERVER, result.getTargetMcpServer());
        }
        return resultMap;
    }

    /**
     * 将 Message 列表格式化为文本（用户/助手对话）
     */
    private String formatHistory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = msg.getMessageType() == MessageType.USER ? "用户" : "助手";
            sb.append(role).append("：").append(msg.getText()).append("\n");
        }
        return sb.toString();
    }
}
