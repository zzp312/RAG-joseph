package com.xushu.rag.graph.nodes;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.service.McpClientManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一工具调用 Node（内部 @Tool + 外部 MCP）
 * <p>内部工具：来自 internalToolCallbackProvider（RagTool 等，页面聊天专用）</p>
 * <p>外部 MCP：来自 mcp_server_config 表（高德地图、紫牛服务等）</p>
 * <p>KnowledgeMcpTools 不在此列——那是给外部 Agent 的 MCP Server 工具。</p>
 * <p>支持多轮链式调用（最多5轮），上下文超长保护。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class ToolCallNode {

    private static final int CHAT_MEMORY_RESPONSE_SIZE = 10;
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final int MAX_TOOL_CONTEXT_CHARS = 8000;

    private static final String[] TOOL_ERROR_KEYWORDS = {
            "token过期", "token无效", "token已过期", "登录已过期", "登录过期",
            "请重新登录", "认证失败", "鉴权失败", "权限不足", "无权限",
            "Unauthorized", "Forbidden", "Access Denied"
    };

    private static final String INTERNAL_SERVER_NAME = "_internal_tools";

    private final ChatClient chatClient;
    private final McpClientManager mcpClientManager;
    private final Map<String, ToolCallback> internalToolMap = new LinkedHashMap<>();
    private String conversationId;
    private String conversationQuestion;

    public ToolCallNode(ChatModel chatModel, ChatMemory chatMemory,
                         McpClientManager mcpClientManager,
                         @Qualifier("internalToolCallbackProvider") ToolCallbackProvider internalToolProvider) {
        this.mcpClientManager = mcpClientManager;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(PromptChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        for (ToolCallback cb : internalToolProvider.getToolCallbacks()) {
            this.internalToolMap.put(cb.getToolDefinition().name(), cb);
        }
        log.info("[ToolCallNode] 内部工具注册: {}", internalToolMap.keySet());
    }

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        this.conversationId = (String) state.getOrDefault(StateKeys.CONVERSATION_ID, "default");
        this.conversationQuestion = question;

        // 1. 加载外部 MCP 工具
        Map<String, List<McpSchema.Tool>> externalTools = loadExternalTools();

        // 2. 构建统一工具列表的 system prompt（含本地+外部）
        String systemPrompt = buildSystemPrompt(externalTools);

        // 3. 多轮工具调用循环
        StringBuilder toolCtx = new StringBuilder();
        toolCtx.append("用户请求：").append(question).append("\n");
        List<String> calledTools = new ArrayList<>();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            log.info("[ToolCallNode] 第{}轮工具调用, toolCtx={}字", round, toolCtx.length());

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

            // 尝试解析JSON工具调用
            String json = extractJson(llmResponse);
            if (json == null) {
                // 无工具调用 → 任务完成
                log.info("[ToolCallNode] 第{}轮LLM未输出工具调用", round);
                String answer = finalizeAnswer(llmResponse, toolCtx, calledTools, systemPrompt, question);
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, answer,
                        StateKeys.STEPS, buildToolSteps(calledTools));
            }

            JSONObject call;
            try {
                call = JSON.parseObject(json);
            } catch (Exception jsonEx) {
                log.warn("[ToolCallNode] JSON解析失败: {}", jsonEx.getMessage());
                return calledTools.isEmpty() ? Map.of(StateKeys.ANSWER, llmResponse)
                        : Map.of(StateKeys.MCP_RESULT, toolCtx.toString(),
                                StateKeys.ANSWER, LLMGenerateNode.stripJsonWrapper(
                                        forceFinalAnswer(systemPrompt, toolCtx.toString(), question)),
                                StateKeys.STEPS, buildToolSteps(calledTools));
            }

            String serverName = call.getString("server_name");
            String toolName = call.getString("tool_name");
            Map<String, Object> arguments = call.getObject("arguments", Map.class);

            if (serverName == null || toolName == null) {
                // 裁判(LLM)判定任务已完成，但未返回合法工具调用 JSON（通常受全局 JSON 格式影响，
                // 把"完成"包装成了 {stepByStepAnalysis,...,finalAnswer}）。直接收口为终态答案，
                // 不再空转重试，避免多轮无效调用与原始 JSON 泄漏。
                log.warn("[ToolCallNode] 非工具调用JSON（缺server_name/tool_name），按终态答案收口");
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, finalizeAnswer(llmResponse, toolCtx, calledTools, systemPrompt, question),
                        StateKeys.STEPS, buildToolSteps(calledTools));
            }

            // 4. 执行工具（内部 or 外部 MCP）
            ToolCallResult toolResult;
            if (INTERNAL_SERVER_NAME.equals(serverName)) {
                toolResult = executeInternalTool(toolName, arguments);
            } else {
                toolResult = executeExternalTool(serverName, toolName, arguments, externalTools);
            }

            log.info("[ToolCallNode] 第{}轮执行: {} → {}, isError={}", round, serverName, toolName, toolResult.isError);
            calledTools.add(toolResult.toolName);

            if (toolResult.isError || isToolResultBusinessError(toolResult.result)) {
                log.warn("[ToolCallNode] 工具 [{}] 失败，停止调用链", toolResult.toolName);
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, "工具 [" + toolResult.toolName + "] 调用失败：" + toolResult.result,
                        StateKeys.STEPS, buildToolSteps(calledTools) + " [已中断]");
            }

            toolCtx.append("\n[已调用: ").append(toolResult.toolName).append("]");
            toolCtx.append("\n[返回: ").append(truncateResult(toolResult.result)).append("]\n");

            if (toolCtx.length() > MAX_TOOL_CONTEXT_CHARS) {
                log.warn("[ToolCallNode] 上下文超长({}字)，强制LLM输出", toolCtx.length());
                return Map.of(
                        StateKeys.MCP_RESULT, toolCtx.toString(),
                        StateKeys.ANSWER, LLMGenerateNode.stripJsonWrapper(
                                forceFinalAnswer(systemPrompt, toolCtx.toString(), question)),
                        StateKeys.STEPS, buildToolSteps(calledTools));
            }
        }

        // 超轮次强制输出
        String forcedAnswer = LLMGenerateNode.stripJsonWrapper(
                forceFinalAnswer(systemPrompt, toolCtx.toString(), question));
        return Map.of(
                StateKeys.MCP_RESULT, toolCtx.toString(),
                StateKeys.ANSWER, forcedAnswer,
                StateKeys.STEPS, buildToolSteps(calledTools));
    }

    // ========== 内部工具执行 ==========

    private ToolCallResult executeInternalTool(String toolName, Map<String, Object> arguments) {
        ToolCallback cb = internalToolMap.get(toolName);
        if (cb == null) {
            return ToolCallResult.error(toolName, "内部工具 [" + toolName + "] 不存在，可用: " + internalToolMap.keySet());
        }
        try {
            // 参数为空时注入原始用户问题（LLM 可能不传 question 参数）
            Map<String, Object> safeArgs = new java.util.LinkedHashMap<>();
            if (arguments != null) {
                safeArgs.putAll(arguments);
            }
            if (!safeArgs.containsKey("question") && !safeArgs.containsKey("query")) {
                safeArgs.put("question", conversationQuestion);
            }
            String toolInput = JSON.toJSONString(safeArgs);
            String result = cb.call(toolInput);
            return ToolCallResult.success(toolName, result != null ? result : "工具执行完成");
        } catch (Exception e) {
            log.error("[ToolCallNode] 内部工具执行异常: tool={}, err={}", toolName, e.getMessage(), e);
            return ToolCallResult.error(toolName, "内部工具执行异常: " + e.getMessage());
        }
    }

    // ========== 外部 MCP 工具执行 ==========

    private ToolCallResult executeExternalTool(String serverName, String toolName,
                                                Map<String, Object> arguments,
                                                Map<String, List<McpSchema.Tool>> externalTools) {
        if (!externalTools.containsKey(serverName)) {
            return ToolCallResult.error(toolName, "MCP服务 [" + serverName + "] 不在可用范围内");
        }

        McpSyncClient client = mcpClientManager.getOrCreateClient(serverName);
        if (client == null) {
            return ToolCallResult.error(toolName, "MCP服务 [" + serverName + "] 连接失败");
        }

        injectTestToken(serverName, arguments);

        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments != null ? arguments : Map.of()));

            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (Object c : result.content()) {
                    sb.append(c instanceof McpSchema.TextContent tc ? tc.text() : c.toString());
                }
            }
            String finalResult = sb.isEmpty() ? "工具执行完成，无返回内容。" : sb.toString();
            log.info("[ToolCallNode] 外部MCP执行成功: tool={}, len={}", toolName, finalResult.length());
            return ToolCallResult.success(toolName, finalResult);
        } catch (Exception e) {
            log.error("[ToolCallNode] 外部MCP执行异常: tool={}, err={}", toolName, e.getMessage(), e);
            return ToolCallResult.error(toolName, "外部MCP异常: " + e.getMessage());
        }
    }

    // ========== 工具加载 & Prompt 构建 ==========

    private Map<String, List<McpSchema.Tool>> loadExternalTools() {
        Map<String, List<McpSchema.Tool>> tools = mcpClientManager.getAllTools();
        log.info("[ToolCallNode] 外部MCP加载: {}", tools.keySet());
        return tools;
    }

    private String buildSystemPrompt(Map<String, List<McpSchema.Tool>> externalTools) {
        StringBuilder sb = new StringBuilder("你是工具调用助手。以下是所有可用工具：\n\n");

        // 内部工具
        if (!internalToolMap.isEmpty()) {
            sb.append("## 内部工具（server_name: ").append(INTERNAL_SERVER_NAME).append("）\n");
            for (ToolCallback cb : internalToolMap.values()) {
                var def = cb.getToolDefinition();
                sb.append("■ server_name: ").append(INTERNAL_SERVER_NAME)
                        .append(", tool_name: ").append(def.name());
                if (def.description() != null && !def.description().isEmpty()) {
                    sb.append(", 描述: ").append(def.description());
                }
                if (def.inputSchema() != null) {
                    sb.append(", 参数: ").append(JSON.toJSONString(def.inputSchema()));
                }
                sb.append("\n");
            }
        }

        // 外部 MCP 工具
        if (!externalTools.isEmpty()) {
            sb.append("\n## 外部MCP工具\n");
            for (Map.Entry<String, List<McpSchema.Tool>> entry : externalTools.entrySet()) {
                for (McpSchema.Tool tool : entry.getValue()) {
                    sb.append("■ server_name: ").append(entry.getKey())
                            .append(", tool_name: ").append(tool.name());
                    if (tool.description() != null && !tool.description().isEmpty()) {
                        sb.append(", 描述: ").append(tool.description());
                    }
                    if (tool.inputSchema() != null) {
                        sb.append(", 参数: ").append(JSON.toJSONString(tool.inputSchema()));
                    }
                    sb.append("\n");
                }
            }
        }

        if (internalToolMap.isEmpty() && externalTools.isEmpty()) {
            sb.append("当前没有任何可用工具。请直接回复用户。");
            return sb.toString();
        }

        sb.append("\n请选择合适的工具，输出JSON格式：\n");
        sb.append("```json\n{\"server_name\":\"服务名\", \"tool_name\":\"工具名\", \"arguments\":{}}\n```\n");
        sb.append("如果不需要调用工具，请直接回复用户。");
        return sb.toString();
    }

    // ========== 辅助方法（与 McpToolCallNode 保持一致） ==========

    private String extractJson(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        int start = text.indexOf("```json");
        if (start != -1) start = text.indexOf("{", start);
        else start = text.indexOf("{");
        if (start == -1) return null;

        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1).trim();
            }
        }
        return null;
    }

    private String truncateResult(String result) {
        if (result == null || result.isEmpty()) return "无返回内容";
        if (result.length() <= 2000) return result;
        return result.substring(0, 2000) + "...[已截断，原文" + result.length() + "字]";
    }

    private boolean isToolResultBusinessError(String result) {
        if (result == null || result.isEmpty()) return false;
        String lower = result.toLowerCase();
        for (String kw : TOOL_ERROR_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private String buildToolSteps(List<String> calledTools) {
        if (calledTools.isEmpty()) return "工具调用: 无工具被触发";
        StringBuilder sb = new StringBuilder("工具调用: ");
        for (int i = 0; i < calledTools.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(calledTools.get(i));
        }
        return sb.toString();
    }

    private Map<String, Object> emptyResult() {
        return Map.of(StateKeys.MCP_RESULT, "", StateKeys.ANSWER, "抱歉，暂时无法处理您的请求。",
                StateKeys.STEPS, "工具调用: 无响应");
    }

    private String forceFinalAnswer(String systemPrompt, String toolContext, String question) {
        String prompt = "你是工具调用助手。以下是已执行的工具调用及结果：\n\n"
                + toolContext + "\n\n"
                + "请基于上述工具执行的结果，用自然语言回答。不要建议调用新工具。";
        try {
            return chatClient.prompt().system(prompt).user("用户原始问题：" + question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).call().content();
        } catch (Exception e) {
            log.error("[ToolCallNode] forceFinalAnswer异常: {}", e.getMessage());
            return "工具执行完成，但整理结果时出现错误。";
        }
    }

    /**
     * 终态答案收口：裁判(LLM)判定"任务完成"时可能返回 {stepByStepAnalysis,...,finalAnswer} 这类 JSON
     * （受全局 JSON 输出格式影响），此处反 JSON 包装提取 finalAnswer 作为纯文本；
     * 若无可提取的 finalAnswer 则退回强制自然语言整理，确保 StateKeys.ANSWER 永远是纯文本，
     * 用户不会看到原始 JSON。
     */
    private String finalizeAnswer(String llmResponse, StringBuilder toolCtx,
                                  List<String> calledTools, String systemPrompt, String question) {
        String unwrapped = LLMGenerateNode.stripJsonWrapper(llmResponse);
        if (unwrapped != null && !unwrapped.trim().equals(llmResponse.trim()) && !unwrapped.isBlank()) {
            return unwrapped;
        }
        // 无 finalAnswer 包装：有工具调用则强制整理一次，否则直接用原文
        String answer = calledTools.isEmpty() ? llmResponse
                : forceFinalAnswer(systemPrompt, toolCtx.toString(), question);
        return LLMGenerateNode.stripJsonWrapper(answer);
    }

    private record ToolCallResult(String toolName, String result, boolean isError) {
        static ToolCallResult success(String name, String r) { return new ToolCallResult(name, r, false); }
        static ToolCallResult error(String name, String r) { return new ToolCallResult(name, r, true); }
    }

    // ==================== 【临时测试逻辑，测完删除】 ====================

    private static final String TEST_TOKEN_SERVER = "ziniu-local-sse";
    private static final String TEST_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ0ZW5hbnRfaWQiOiIxIiwidGVuYW50X25hbWUiOiLljaHmgJ3kvJjmtL4iLCJhY2NvdW50X3R5cGUiOiJzeXNfdXNlciIsInVzZXJfbmFtZSI6Imxlby53YW5nQHlwaHJzLmNvbSIsImRlcHRfbmFtZSI6IueZvemihuWkluWMhemhueebruS4gOe7hCIsImVuZ2xpc2hfbmFtZSI6ImxpYW5nbGlhbmctdyIsImNsaWVudF9pZCI6Inppbml1IiwidXNlcl9pZCI6IjE5NDQ2NDk1NjEyMTc4MzA5MTIiLCJzY29wZSI6WyJhbGwiXSwibmFtZSI6Imxlb-S6riIsImRlcHRfaWQiOiIxOTU1MTQ0NDAwNTQ4MDA3OTM2IiwiZXhwIjoxNzg0MDA5NDk4LCJqdGkiOiJiODVhZDI1Zi0zOWQwLTRlZmMtODZjMy1iMWM4ZWEwZDI3ZWMiLCJ1c2VybmFtZSI6Imxlby53YW5nQHlwaHJzLmNvbSIsInRlbmFudF9jb2RlIjoiQ1NDIn0.Iqct5a31yb4h-TC1NrGTQNwSkhbWWh-1f2UFMITESHM";

    private static void injectTestToken(String serverName, Map<String, Object> arguments) {
        if (!TEST_TOKEN_SERVER.equals(serverName) || arguments == null) return;
        arguments.put("token", TEST_TOKEN);
    }
}
