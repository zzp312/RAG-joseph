package com.xushu.rag.graph.nodes;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.service.McpClientManager;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP工具调用Node（真实调用）
 * <p>根据用户请求精准匹配目标MCP服务，只将目标服务的工具传给LLM，
 * 避免LLM自由选工具导致调用错误的服务或工具调用失败后fallback到其他工具</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class McpToolCallNode {

    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;

    private final ChatClient chatClient;
    private final McpClientManager mcpClientManager;
    private String conversationId;

    public McpToolCallNode(ChatModel chatModel, ChatMemory chatMemory,
                           McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(PromptChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        this.conversationId = (String) state.getOrDefault(StateKeys.CONVERSATION_ID, "default");

        // 1. 确定目标MCP服务（优先state指定，其次关键词匹配）
        String targetServer = resolveTargetServer(state, question);

        // 2. 只加载目标服务的工具
        Map<String, List<McpSchema.Tool>> tools;
        if (targetServer != null && !targetServer.isEmpty()) {
            List<McpSchema.Tool> serverTools = mcpClientManager.getTools(targetServer);
            if (serverTools == null || serverTools.isEmpty()) {
                String msg = "指定的MCP服务 [" + targetServer + "] 无可用工具，请检查服务配置。";
                log.warn("[McpToolCall] {}", msg);
                return Map.of(
                        StateKeys.MCP_RESULT, msg,
                        StateKeys.ANSWER, msg,
                        StateKeys.STEPS, "MCP工具调用: 目标服务无可用工具");
            }
            tools = Map.of(targetServer, serverTools);
            log.info("[McpToolCall] 已锁定目标服务: {}, 工具数: {}", targetServer, serverTools.size());
        } else {
            tools = mcpClientManager.getAllTools();
            if (tools.isEmpty()) {
                String msg = "暂无可用的MCP工具服务，请联系管理员配置。";
                return Map.of(
                        StateKeys.MCP_RESULT, msg,
                        StateKeys.ANSWER, msg,
                        StateKeys.STEPS, "MCP工具调用: 无可用工具");
            }
            log.info("[McpToolCall] 未指定目标服务，加载全部可用工具: {}", tools.keySet());
        }

        // 3. 构建提示词（只含目标服务的工具）
        String systemPrompt = buildSystemPrompt(tools, targetServer);
        String userPrompt = "用户请求：" + question + "\n\n请根据上方工具列表，选择合适的工具执行操作。";

        try {
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, this.conversationId)
                            .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                    .call()
                    .content();

            // 4. 解析LLM输出的工具调用JSON并执行
            McpToolCallResult toolResult = tryExecuteToolCall(answer, tools);

            if (toolResult != null) {
                log.info("[McpToolCall] 工具调用成功, tool={}", toolResult.toolName);
                String naturalAnswer = formatToolResult(toolResult.toolName, toolResult.result, question);
                return Map.of(
                        StateKeys.MCP_RESULT, toolResult.result,
                        StateKeys.ANSWER, naturalAnswer,
                        StateKeys.STEPS, "MCP工具调用成功: " + toolResult.toolName);
            }

            // 5. LLM未输出工具调用JSON，返回其直接回答
            return Map.of(
                    StateKeys.MCP_RESULT, answer,
                    StateKeys.ANSWER, answer,
                    StateKeys.STEPS, "MCP工具调用: 未触发工具");

        } catch (Exception e) {
            log.error("[McpToolCall] 调用失败: {}", e.getMessage(), e);
            return Map.of(
                    StateKeys.MCP_RESULT, "MCP工具调用异常: " + e.getMessage(),
                    StateKeys.ANSWER, "抱歉，工具调用出了点问题，请稍后重试。",
                    StateKeys.STEPS, "MCP工具调用异常");
        }
    }

    /**
     * 确定目标MCP服务名
     * <p>优先级：state指定 > 关键词匹配 > null（返回全部）
     */
    private String resolveTargetServer(Map<String, Object> state, String question) {
        // 方式1：state中已指定（由IntentClassifyNode或前端写入）
        String fromState = (String) state.get(StateKeys.TARGET_MCP_SERVER);
        if (fromState != null && !fromState.isEmpty()) {
            log.info("[McpToolCall] 从state读取目标服务: {}", fromState);
            return fromState;
        }

        // 方式2：按问题关键词匹配服务名/工具名（简单启发式兜底）
        String matched = matchServerByKeyword(question);
        if (matched != null) {
            log.info("[McpToolCall] 关键词匹配到目标服务: {}, question={}", matched, question);
            return matched;
        }

        return null;
    }

    /**
     * 按问题关键词匹配最可能的MCP服务（启发式兜底）
     */
    private String matchServerByKeyword(String question) {
        String q = question.toLowerCase();
        Map<String, List<McpSchema.Tool>> allTools = mcpClientManager.getAllTools();
        if (allTools.isEmpty()) return null;

        // 服务名关键词映射（可按需扩展）
        Map<String, String[]> serverKeywords = Map.of(
                "amap-maps", new String[]{"高德", "地图", "导航", "路线", "地址", "poi", "amap"},
                "ziniu-local-server", new String[]{"紫牛", "ziniu", "本地", "local"}
        );

        for (Map.Entry<String, String[]> entry : serverKeywords.entrySet()) {
            String serverName = entry.getKey();
            for (String kw : entry.getValue()) {
                if (q.contains(kw) && allTools.containsKey(serverName)) {
                    return serverName;
                }
            }
        }

        // 工具名关键词匹配
        for (Map.Entry<String, List<McpSchema.Tool>> entry : allTools.entrySet()) {
            for (McpSchema.Tool tool : entry.getValue()) {
                if (containsToolKeyword(q, tool)) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    /**
     * 判断问题是否包含工具相关关键词
     */
    private boolean containsToolKeyword(String question, McpSchema.Tool tool) {
        String toolName = tool.name().toLowerCase();
        String desc = tool.description() != null ? tool.description().toLowerCase() : "";

        // 从toolName提取核心词匹配
        String[] parts = toolName.split("[_\\-]");
        for (String part : parts) {
            if (part.length() >= 3 && question.contains(part)) {
                return true;
            }
        }
        // description关键词匹配
        if (!desc.isEmpty()) {
            String[] qWords = question.split("\\s+");
            for (String w : qWords) {
                if (w.length() >= 2 && desc.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 构建系统提示词（只含指定服务的工具，并强调不要调用其他服务）
     */
    private String buildSystemPrompt(Map<String, List<McpSchema.Tool>> tools, String targetServer) {
        StringBuilder sb = new StringBuilder("你是工具调用助手。");
        if (targetServer != null) {
            sb.append("用户请求需要调用 [").append(targetServer).append("] 服务，请只从该服务的工具中选择。\n\n");
        } else {
            sb.append("有以下MCP工具可用：\n\n");
        }

        for (Map.Entry<String, List<McpSchema.Tool>> entry : tools.entrySet()) {
            String serverName = entry.getKey();
            for (McpSchema.Tool tool : entry.getValue()) {
                sb.append("■ 服务:").append(serverName)
                        .append(", 工具:").append(tool.name());
                if (tool.description() != null && !tool.description().isEmpty()) {
                    sb.append(", 描述:").append(tool.description());
                }
                if (tool.inputSchema() != null) {
                    sb.append(", 参数:").append(JSON.toJSONString(tool.inputSchema()));
                }
                sb.append("\n");
            }
        }

        sb.append("\n请选择一个合适的工具，输出JSON格式：\n");
        sb.append("```json\n{\"server_name\":\"服务名\", \"tool_name\":\"工具名\", \"arguments\":{}}\n```\n");
        sb.append("如果不需要调用工具，请直接回复用户。");
        return sb.toString();
    }

    /**
     * 从LLM回复中提取JSON并执行工具调用
     * <p>修复：工具调用失败时返回包含错误信息的McpToolCallResult，不再返回null导致fallback
     */
    @SuppressWarnings("unchecked")
    private McpToolCallResult tryExecuteToolCall(String llmResponse,
                                                  Map<String, List<McpSchema.Tool>> allowedTools) {
        try {
            String json = extractJson(llmResponse);
            if (json == null) {
                log.warn("[McpToolCall] LLM回复中未找到JSON工具调用: {}",
                        llmResponse.length() > 200 ? llmResponse.substring(0, 200) : llmResponse);
                return null;
            }

            JSONObject call = JSON.parseObject(json);
            String serverName = call.getString("server_name");
            String toolName = call.getString("tool_name");
            Map<String, Object> arguments = call.getObject("arguments", Map.class);

            if (serverName == null || toolName == null) {
                log.warn("[McpToolCall] LLM输出的工具调用JSON缺少必要字段: {}", json);
                return null;
            }

            // 校验：LLM选择的服务器是否在允许列表中
            if (!allowedTools.containsKey(serverName)) {
                String err = "服务 [" + serverName + "] 不在当前可用范围内，请只选择已列出的服务。";
                log.warn("[McpToolCall] LLM选择了未授权的服务: {}", serverName);
                return new McpToolCallResult(toolName, err);
            }

            io.modelcontextprotocol.client.McpSyncClient client =
                    mcpClientManager.getOrCreateClient(serverName);
            if (client == null) {
                String err = "服务 [" + serverName + "] 连接失败，该服务可能未启动或配置有误。";
                log.warn("[McpToolCall] {}", err);
                return new McpToolCallResult(toolName, err);
            }

            log.info("[McpToolCall] 执行工具: server={}, tool={}, args={}",
                    serverName, toolName, arguments);

            io.modelcontextprotocol.spec.McpSchema.CallToolResult result =
                    client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                            toolName, arguments != null ? arguments : Map.of()));

            StringBuilder resultText = new StringBuilder();
            if (result.content() != null) {
                for (Object content : result.content()) {
                    if (content instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc) {
                        resultText.append(tc.text());
                    } else {
                        resultText.append(content.toString());
                    }
                }
            }

            String finalResult = resultText.isEmpty() ? "工具执行完成，无返回内容。" : resultText.toString();
            log.info("[McpToolCall] 工具执行成功: tool={}, resultLen={}", toolName, finalResult.length());
            return new McpToolCallResult(toolName, finalResult);

        } catch (Exception e) {
            log.error("[McpToolCall] 工具执行异常: {}", e.getMessage(), e);
            // 修复：返回错误信息而非null，避免调用方fallback到其他工具
            String toolName = "unknown";
            try {
                String json = extractJson(llmResponse);
                if (json != null) {
                    JSONObject call = JSON.parseObject(json);
                    if (call != null && call.containsKey("tool_name")) {
                        toolName = call.getString("tool_name");
                    }
                }
            } catch (Exception ignored) {}
            return new McpToolCallResult(toolName, "工具执行异常: " + e.getMessage());
        }
    }

    /**
     * 让 LLM 把工具结果整理成自然语言
     */
    private String formatToolResult(String toolName, String rawResult, String question) {
        try {
            String systemPrompt = "你是Joseph.zhou知识库系统的助手，负责把工具返回的数据整理成自然、友好的中文回答。\n"
                    + "要求：\n"
                    + "1. 用自然语言回答用户问题，不要直接展示JSON\n"
                    + "2. 提取关键信息并组织成清晰的文字\n"
                    + "3. 可以用列表或分段让内容更易读\n"
                    + "4. 简洁明了，不要编造工具结果中没有的信息";
            String userPrompt = "用户问题：" + question + "\n\n"
                    + "工具 " + toolName + " 返回的原始数据：\n" + rawResult + "\n\n"
                    + "请把上述数据整理成自然语言回答用户。";

            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[McpToolCall] 整理工具结果失败: {}", e.getMessage(), e);
            return rawResult;
        }
    }

    /**
     * 从LLM回复中提取JSON块（支持 ```json 代码块 或 裸JSON）
     */
    private String extractJson(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        int start = text.indexOf("```json");
        if (start != -1) {
            start = text.indexOf("{", start);
        } else {
            start = text.indexOf("{");
        }
        if (start == -1) return null;

        int end = text.lastIndexOf("}");
        if (end == -1 || end <= start) return null;

        return text.substring(start, end + 1).trim();
    }

    /**
     * 工具调用结果
     */
    private record McpToolCallResult(String toolName, String result) {
    }
}
