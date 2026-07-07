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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP工具调用Node（真实调用，支持多轮工具链式调用）
 * <p>根据用户请求精准匹配目标MCP服务，加载工具交由LLM多轮迭代选择，
 * 每轮工具执行结果追加到上下文后让LLM判断是否需要继续调用下一个工具，
 * 最多循环5轮防止无限调用。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class McpToolCallNode {

    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;

    /** 工具调用循环最大轮次 */
    private static final int MAX_TOOL_ROUNDS = 5;

    /** 工具调用上下文累计最大字符数（防止token爆炸） */
    private static final int MAX_TOOL_CONTEXT_CHARS = 8000;

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

        // 1. 确定目标MCP服务、加载工具
        String targetServer = resolveTargetServer(state, question);
        Map<String, List<McpSchema.Tool>> tools = loadTools(targetServer);
        if (tools == null) {
            return Map.of(
                    StateKeys.MCP_RESULT, "无可用MCP工具",
                    StateKeys.ANSWER, "暂无可用的MCP工具服务，请联系管理员配置。",
                    StateKeys.STEPS, "MCP工具调用: 无可用工具");
        }

        // 2. 多轮工具调用循环
        StringBuilder toolCtx = new StringBuilder();
        toolCtx.append("用户请求：").append(question).append("\n");
        String systemPrompt = buildSystemPrompt(tools, targetServer);
        List<String> calledTools = new ArrayList<>();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            log.info("[McpToolCall] 第{}轮工具调用, toolCtx={}字", round, toolCtx.length());

            String llmResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(toolCtx.toString()
                            + "\n请选择下一个工具执行（输出JSON），或如果任务已完成请直接回复用户。")
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, this.conversationId)
                            .param("chat_memory_response_size", CHAT_MEMORY_RESPONSE_SIZE))
                    .call()
                    .content();

            if (llmResponse == null || llmResponse.trim().isEmpty()) {
                return emptyResult();
            }

            // 尝试解析工具调用JSON
            McpToolCallResult toolResult = tryExecuteToolCall(llmResponse, tools);

            if (toolResult == null) {
                // LLM 没有输出工具调用JSON → 认为已完成，返回其直接回答
                log.info("[McpToolCall] 第{}轮LLM未输出工具调用，返回最终答案", round);
                String naturalAnswer = formatToolResult("", llmResponse, question);
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, naturalAnswer,
                        StateKeys.STEPS, buildToolSteps(calledTools));
            }

            // 有工具调用 → 执行并追加结果
            log.info("[McpToolCall] 第{}轮执行工具: {}", round, toolResult.toolName);
            calledTools.add(toolResult.toolName);
            toolCtx.append("\n[已调用工具: ").append(toolResult.toolName).append("]");
            toolCtx.append("\n[工具返回: ").append(truncateResult(toolResult.result))
                    .append("]\n");

            // 上下文超长保护 → 强制LLM基于现有信息给出最终答案
            if (toolCtx.length() > MAX_TOOL_CONTEXT_CHARS) {
                log.warn("[McpToolCall] 工具上下文超长({}字)，强制LLM输出最终答案", toolCtx.length());
                String forcedAnswer = forceFinalAnswer(systemPrompt, toolCtx.toString(), question);
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, forcedAnswer,
                        StateKeys.STEPS, buildToolSteps(calledTools));
            }
        }

        // 超过最大轮次 → 强制LLM给出最终答案
        log.warn("[McpToolCall] 达到最大轮次{}，强制LLM输出最终答案", MAX_TOOL_ROUNDS);
        String forcedAnswer = forceFinalAnswer(systemPrompt, toolCtx.toString(), question);
        return Map.of(
                StateKeys.MCP_RESULT, toolCtx.toString(),
                StateKeys.ANSWER, forcedAnswer,
                StateKeys.STEPS, buildToolSteps(calledTools));
    }

    /**
     * 确定目标MCP服务名。
     * <p>state 中已指定（由 IntentClassifyNode 或前端写入）则直接用；
     * 未指定时返回 null，下游加载全部工具由 LLM 根据描述自行选择。</p>
     */
    private String resolveTargetServer(Map<String, Object> state, String question) {
        String fromState = (String) state.get(StateKeys.TARGET_MCP_SERVER);
        if (fromState != null && !fromState.isEmpty()) {
            log.info("[McpToolCall] 从state读取目标服务: {}", fromState);
            return fromState;
        }
        return null;
    }

    /**
     * 加载工具列表。指定服务则只加载该服务，否则加载全部。
     * @return 工具列表，无可用工具时返回 null
     */
    private Map<String, List<McpSchema.Tool>> loadTools(String targetServer) {
        Map<String, List<McpSchema.Tool>> tools;
        if (targetServer != null && !targetServer.isEmpty()) {
            List<McpSchema.Tool> serverTools = mcpClientManager.getTools(targetServer);
            if (serverTools == null || serverTools.isEmpty()) {
                log.warn("[McpToolCall] 指定的MCP服务 [{}] 无可用工具", targetServer);
                return null;
            }
            tools = Map.of(targetServer, serverTools);
            log.info("[McpToolCall] 已锁定目标服务: {}, 工具数: {}", targetServer, serverTools.size());
        } else {
            tools = mcpClientManager.getAllTools();
            if (tools.isEmpty()) {
                log.warn("[McpToolCall] 暂无可用的MCP工具服务");
                return null;
            }
            log.info("[McpToolCall] 未指定目标服务，加载全部可用工具: {}", tools.keySet());
        }
        return tools;
    }

    /**
     * 截断过长的工具返回结果，控制上下文膨胀。
     */
    private String truncateResult(String result) {
        if (result == null || result.isEmpty()) return "无返回内容";
        if (result.length() <= 2000) return result;
        return result.substring(0, 2000) + "...[已截断，原文" + result.length() + "字]";
    }

    /**
     * 将已调用的工具名格式化为步骤展示字符串。
     */
    private String buildToolSteps(List<String> calledTools) {
        if (calledTools.isEmpty()) {
            return "MCP工具调用: 无工具被触发";
        }
        StringBuilder sb = new StringBuilder("MCP工具调用: ");
        for (int i = 0; i < calledTools.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(calledTools.get(i));
        }
        return sb.toString();
    }

    /**
     * 空结果兜底。
     */
    private Map<String, Object> emptyResult() {
        return Map.of(
                StateKeys.MCP_RESULT, "",
                StateKeys.ANSWER, "抱歉，暂时无法处理您的请求。",
                StateKeys.STEPS, "MCP工具调用: 无响应");
    }

    /**
     * 强制LLM基于当前工具执行上下文给出最终答案。
     */
    private String forceFinalAnswer(String systemPrompt, String toolContext, String question) {
        String prompt = "你是工具调用助手。以下是已执行的工具调用及结果：\n\n"
                + toolContext + "\n\n"
                + "请基于上述工具执行的结果，用自然语言回答用户的原始问题。\n"
                + "不要建议调用新工具。如果信息不足，请告知用户当前已知的信息。";
        try {
            return chatClient.prompt()
                    .system(prompt)
                    .user("用户原始问题：" + question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[McpToolCall] 强制输出最终答案失败: {}", e.getMessage());
            return "工具执行完成，但整理结果时出现错误，请稍后重试。";
        }
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
     * 让 LLM 把工具结果整理成自然语言。
     * <p>当 toolName 为空时（LLM 直接输出最终回答），直接返回原文本。</p>
     */
    private String formatToolResult(String toolName, String rawResult, String question) {
        if (toolName == null || toolName.isEmpty()) {
            return rawResult;
        }
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
