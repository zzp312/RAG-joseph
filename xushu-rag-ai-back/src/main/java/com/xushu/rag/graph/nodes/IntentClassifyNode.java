package com.xushu.rag.graph.nodes;

import com.xushu.rag.classifier.IntentClassifier;
import com.xushu.rag.classifier.KeywordIntentClassifier;
import com.xushu.rag.classifier.LLMIntentClassifier;
import com.xushu.rag.entity.McpServerConfig;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.mapper.McpServerConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final McpServerConfigMapper mcpServerConfigMapper;

    public IntentClassifyNode(KeywordIntentClassifier keywordClassifier,
                              LLMIntentClassifier llmClassifier,
                              ChatMemory chatMemory,
                              McpServerConfigMapper mcpServerConfigMapper) {
        this.keywordClassifier = keywordClassifier;
        this.llmClassifier = llmClassifier;
        this.chatMemory = chatMemory;
        this.mcpServerConfigMapper = mcpServerConfigMapper;
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

        // L1: 关键词快速路由（0 token），仅首轮无历史时启用
        // 多轮对话中 follow-up 消息的意图高度依赖上下文（如"那你帮我算一下嘛"），
        // 关键词无法准确判断，交由 L2 LLM 结合历史做分类
        IntentClassifier.ClassifyResult result;
        if (historyMessages != null && !historyMessages.isEmpty()) {
            result = null; // 有对话历史 → 跳过L1关键词
        } else {
            result = keywordClassifier.classify(question);
        }
        if (result == null) {
            // L2: LLM兜底（~200 token，带对话历史）
            result = llmClassifier.classify(question, historyText);
        }

        String category = result.getCategory().getValue();

        // 覆写：检测对比/聚合语义
        // 仅当 LLM 未分类为 operation，且 toolConfirm=false 时才允许关键词覆写
        // 避免将明确的操作类请求（如"查XX有哪些字段"）误判为 aggregation/comparison
        String q = question.toLowerCase();
        boolean isOperation = "operation".equals(result.getCategory().getValue());
        boolean toolConfirm = result.isToolConfirm();

        if (!isOperation && !toolConfirm) {
            if (q.contains("区别") || q.contains("对比") || q.contains("比较") || q.contains("哪个更好")
                    || q.contains("优缺点") || q.contains("异同") || q.contains("差异")) {
                category = "comparison";
            } else if (q.contains("汇总") || q.contains("统计") || q.contains("最多") || q.contains("最少")
                    || q.contains("列出所有") || q.contains("有哪些")) {
                category = "aggregation";
            }
        }

        // 覆写：operation 误判为 planning（隐患1修复）
        // 当 LLM 把包含规划特征词的 query 误判为 operation 时，覆写回 planning
        // 避免规划类问题被直接路由到 mcp_tool_call，跳过检索和规划
        // 【已注释】暂不启用覆写，先依赖 intent-classify.st 中的优先级规则约束。
        // 如需启用，取消下方 if 块的注释即可。
        boolean finalToolConfirm = result.isToolConfirm();
        // if (isOperation) {
        //     if (q.contains("计划") || q.contains("方案") || q.contains("安排")
        //             || q.contains("清单") || q.contains("规划")) {
        //         category = "planning";
        //         finalToolConfirm = false;
        //         log.info("[IntentClassify] 覆写: operation → planning (query包含规划特征词, toolConfirm重置为false)");
        //     }
        // }

        // 后校验：targetMcpServer 必须匹配真实启用的MCP服务名，否则纠偏或清空
        String targetMcpServer = validateAndCorrectMcpServer(
                result.getTargetMcpServer(), question);

        log.info("[IntentClassify] question='{}', category={}, layer={}, tokenUsed={}, emotion={}, toolConfirm={}, targetMcpServer={}, answerType={}, historyMsg={}",
                question.length() > 40 ? question.substring(0, 40) + "..." : question,
                category, result.getLayer(), result.getTokenUsed(), result.getEmotion(),
                finalToolConfirm, targetMcpServer, result.getAnswerType(), historyMessages.size());

        Map<String, Object> resultMap = new java.util.HashMap<>();
        resultMap.put(StateKeys.CATEGORY, category);
        resultMap.put(StateKeys.EMOTION, result.getEmotion());
        resultMap.put(StateKeys.TOOL_CONFIRM, finalToolConfirm);
        resultMap.put(StateKeys.ANSWER_TYPE, result.getAnswerType());
        resultMap.put(StateKeys.STEPS, "意图分类完成: " + category + " (" + result.getLayer() + "), answerType=" + result.getAnswerType());
        // 写入目标MCP服务名（已校验纠偏）
        if (targetMcpServer != null && !targetMcpServer.isEmpty()) {
            resultMap.put(StateKeys.TARGET_MCP_SERVER, targetMcpServer);
        }
        return resultMap;
    }

    /**
     * 校验 LLM 返回的 targetMcpServer 是否为真实启用的服务名。
     * <p>LLM 可能编造不存在的服务名（如 map-service），
     * 此方法查询数据库中的真实启用服务列表进行校验，
     * 命中则通过，未命中则清空交由下游 McpToolCallNode 加载全部工具做智能匹配。</p>
     */
    private String validateAndCorrectMcpServer(String llmServer, String question) {
        if (llmServer == null || llmServer.trim().isEmpty()) {
            return "";
        }

        // 查询真实启用的MCP服务名集合
        Set<String> validNames;
        try {
            List<McpServerConfig> configs = mcpServerConfigMapper.selectAllEnabled();
            if (configs == null || configs.isEmpty()) {
                log.warn("[IntentClassify] 无可用MCP服务，清空 targetMcpServer");
                return "";
            }
            validNames = configs.stream()
                    .map(McpServerConfig::getServerName)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[IntentClassify] 查询MCP服务列表失败，保留原始值: {}", e.getMessage());
            return llmServer;
        }

        // 直接命中 → 通过
        if (validNames.contains(llmServer)) {
            return llmServer;
        }

        // 未命中：LLM编造的服务名，清空交由 McpToolCallNode 加载全部工具智能匹配
        log.warn("[IntentClassify] LLM返回了不存在的服务名 [{}]，清空 targetMcpServer，"
                + "交由 McpToolCallNode 加载全部工具", llmServer);
        return "";
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
