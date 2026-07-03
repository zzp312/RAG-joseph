package com.xushu.rag.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xushu.rag.graph.nodes.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * Graph Agent 构建器（Spring AI Alibaba Graph）
 * <p>
 * 工作流链路:
 * START → QuestionInput → IntentClassify → 条件边(emotion/category) →
 *   → [负面] Escalation → END
 *   → [操作确认] McpToolCall → END
 *   → [操作] PromptRoute → LLMGenerate → END
 *   → [拆解] QueryDecompose → PromptRoute → ...
 *   → [其他] PromptRoute → Retrieval → ContextBuild → LLMGenerate → END
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Service
public class RagGraphAgent {

    private final QuestionInputNode questionInputNode;
    private final IntentClassifyNode intentClassifyNode;
    private final PromptRouteNode promptRouteNode;
    private final RetrievalNode retrievalNode;
    private final ContextBuildNode contextBuildNode;
    private final LLMGenerateNode llmGenerateNode;
    private final QueryDecomposeNode queryDecomposeNode;
    private final EscalationNode escalationNode;
    private final McpToolCallNode mcpToolCallNode;
    private final ChatModel chatModel;
    /** SSE 回调专用线程池，避免回调阻塞 Graph 节点链路 */
    private final ThreadPoolExecutor graphCallbackExecutor;

    public RagGraphAgent(QuestionInputNode questionInputNode,
                         IntentClassifyNode intentClassifyNode,
                         PromptRouteNode promptRouteNode,
                         RetrievalNode retrievalNode,
                         ContextBuildNode contextBuildNode,
                         LLMGenerateNode llmGenerateNode,
                         QueryDecomposeNode queryDecomposeNode,
                         EscalationNode escalationNode,
                         McpToolCallNode mcpToolCallNode,
                         ChatModel chatModel,
                         @Qualifier("graphCallbackExecutor") ThreadPoolExecutor graphCallbackExecutor) {
        this.questionInputNode = questionInputNode;
        this.intentClassifyNode = intentClassifyNode;
        this.promptRouteNode = promptRouteNode;
        this.retrievalNode = retrievalNode;
        this.contextBuildNode = contextBuildNode;
        this.llmGenerateNode = llmGenerateNode;
        this.queryDecomposeNode = queryDecomposeNode;
        this.escalationNode = escalationNode;
        this.mcpToolCallNode = mcpToolCallNode;
        this.chatModel = chatModel;
        this.graphCallbackExecutor = graphCallbackExecutor;
    }

    /**
     * 构建并编译 Graph Agent 工作流
     * <p>子类可覆盖 registerNodes() 和 wireEdges() 扩展新节点（Phase 2预留）</p>
     *
     * @return 可执行的 CompiledGraph
     */
    public CompiledGraph buildGraph() throws Exception {
        return buildGraphWithCallback(null);
    }

    /**
     * 构建并编译 Graph Agent 工作流（带节点完成回调）
     * <p>用于实现实时 SSE 推送，每个节点完成时立即回调</p>
     *
     * @param nodeCallback 节点完成回调，参数为 (nodeName, result, timestamp)
     * @return 可执行的 CompiledGraph
     */
    public CompiledGraph buildGraphWithCallback(
            java.util.function.Consumer<Map<String, Object>> nodeCallback) throws Exception {
        // 状态更新策略：全部使用覆盖策略（Phase 2新增key自动纳入）
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : ALL_STATE_KEYS) {
                strategies.put(key, new ReplaceStrategy());
            }
            return strategies;
        };

        StateGraph stateGraph = new StateGraph(keyStrategyFactory);

        // 子步骤1：注册节点（可扩展）
        registerNodes(stateGraph, nodeCallback);

        // 子步骤2：连接边（可扩展，支持条件分支）
        wireEdges(stateGraph);

        CompiledGraph compiledGraph = stateGraph.compile();
        log.info("[RagGraphAgent] Graph编译完成，节点数={}", ALL_STATE_KEYS.length);
        return compiledGraph;
    }

    /** 无需检索的意图分类（闲聊+操作+转人工，直接LLM回复或走MCP） */
    private static final java.util.Set<String> SKIP_RETRIEVAL_CATEGORIES =
            java.util.Set.of("chitchat", "unknown", "operation", "escalation");

    /** 判断当前分类是否需要跳过检索节点 */
    private static boolean isSkipRetrieval(String category) {
        return SKIP_RETRIEVAL_CATEGORIES.contains(category != null ? category.toLowerCase() : "");
    }

    /** 所有State Key（新增key在此追加即可） */
    private static final String[] ALL_STATE_KEYS = {
            StateKeys.QUESTION, StateKeys.CATEGORY, StateKeys.SYSTEM_PROMPT,
            StateKeys.TEMPLATE_ID, StateKeys.TEMPLATE_NAME,
            StateKeys.KB_IDS, StateKeys.SOURCES, StateKeys.EFFECTIVE_KB_ID,
            StateKeys.CONVERSATION_ID, StateKeys.DOCUMENTS, StateKeys.CONTEXT,
            StateKeys.ANSWER, StateKeys.STEPS, StateKeys.STEP_TYPE,
            StateKeys.SUB_QUERIES,
            StateKeys.EMOTION, StateKeys.ESCALATE, StateKeys.MCP_RESULT, StateKeys.TOKEN_USAGE,
            StateKeys.CANCELLED, StateKeys.TOOL_CONFIRM,
            StateKeys.TARGET_MCP_SERVER
    };

    /** 注册所有节点（Phase 2在此追加新节点） */
    protected void registerNodes(StateGraph stateGraph) throws GraphStateException {
        registerNodes(stateGraph, null);
    }

    /** 注册所有节点（带回调） */
    protected void registerNodes(StateGraph stateGraph,
                                  java.util.function.Consumer<Map<String, Object>> nodeCallback) throws GraphStateException {
        addNode(stateGraph, "question_input", questionInputNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "intent_classify", intentClassifyNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "query_decompose", queryDecomposeNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "prompt_route", promptRouteNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "retrieval", retrievalNode, StateKeys.StepType.TOOL, nodeCallback);
        addNode(stateGraph, "context_build", contextBuildNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "llm_generate", llmGenerateNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "escalation", escalationNode, StateKeys.StepType.THINKING, nodeCallback);
        addNode(stateGraph, "mcp_tool_call", mcpToolCallNode, StateKeys.StepType.TOOL, nodeCallback);
    }

    /** 连接所有边（含情绪检测+转人工+MCP工具调用分支） */
    protected void wireEdges(StateGraph stateGraph) throws GraphStateException {
        stateGraph.addEdge(START, "question_input");
        stateGraph.addEdge("question_input", "intent_classify");

        // 条件边：intent_classify → 3路分支
        // ① emotion=negative 或 category=escalation → 转人工
        // ② category=operation → 判断是否确认执行 → mcp_tool_call 或 prompt_route
        // ③ comparison/aggregation → 拆解节点
        // ④ 其他 → prompt_route
        stateGraph.addConditionalEdges("intent_classify",
                state -> java.util.concurrent.CompletableFuture.completedFuture(
                        resolveIntentBranch(state.data())),
                Map.of("escalation", "escalation",
                        "mcp_tool_call", "mcp_tool_call",
                        "query_decompose", "query_decompose",
                        "prompt_route", "prompt_route"));

        // 转人工 → END
        stateGraph.addEdge("escalation", END);
        // MCP工具调用 → END
        stateGraph.addEdge("mcp_tool_call", END);
        // 拆解 → prompt_route
        stateGraph.addEdge("query_decompose", "prompt_route");

        // 条件边：prompt_route → 跳过检索则直接LLM，否则走检索
        stateGraph.addConditionalEdges("prompt_route",
                state -> java.util.concurrent.CompletableFuture.completedFuture(
                        isSkipRetrieval((String) state.data().getOrDefault(StateKeys.CATEGORY, ""))
                                ? "llm_generate" : "retrieval"),
                Map.of("retrieval", "retrieval", "llm_generate", "llm_generate"));

        stateGraph.addEdge("retrieval", "context_build");
        stateGraph.addEdge("context_build", "llm_generate");
        stateGraph.addEdge("llm_generate", END);
    }

    /**
     * 根据 category + emotion + toolConfirm 判断 intent_classify 后的分支走向
     */
    private static String resolveIntentBranch(Map<String, Object> state) {
        String category = (String) state.getOrDefault(StateKeys.CATEGORY, "");
        String emotion = (String) state.getOrDefault(StateKeys.EMOTION, "neutral");
        Boolean toolConfirm = (Boolean) state.getOrDefault(StateKeys.TOOL_CONFIRM, false);

        // ① 情绪负面 或 用户明确要求转人工
        if ("negative".equalsIgnoreCase(emotion)
                || "escalation".equalsIgnoreCase(category)) {
            return "escalation";
        }

        // ② 操作类：根据LLM判断的toolConfirm决定走向
        if ("operation".equalsIgnoreCase(category)) {
            if (Boolean.TRUE.equals(toolConfirm)) {
                return "mcp_tool_call";
            }
            return "prompt_route";
        }

        // ③ 对比/汇总 → 拆解
        if ("comparison".equalsIgnoreCase(category)
                || "aggregation".equalsIgnoreCase(category)) {
            return "query_decompose";
        }

        // ④ 默认直连模板路由
        return "prompt_route";
    }

    /** 通用节点注册：同步Node → AsyncNodeAction + stepType写入 */
    private <T> void addNode(StateGraph graph, String name, T node, String stepType)
            throws GraphStateException {
        addNode(graph, name, node, stepType, null);
    }

    /** 通用节点注册（带回调） */
    private <T> void addNode(StateGraph graph, String name, T node, String stepType,
                              java.util.function.Consumer<Map<String, Object>> nodeCallback)
            throws GraphStateException {
        java.util.function.Function<Map<String, Object>, Map<String, Object>> fn;
        if (node instanceof QuestionInputNode) fn = ((QuestionInputNode) node)::apply;
        else if (node instanceof IntentClassifyNode) fn = ((IntentClassifyNode) node)::apply;
        else if (node instanceof PromptRouteNode) fn = ((PromptRouteNode) node)::apply;
        else if (node instanceof RetrievalNode) fn = ((RetrievalNode) node)::apply;
        else if (node instanceof ContextBuildNode) fn = ((ContextBuildNode) node)::apply;
        else if (node instanceof LLMGenerateNode) fn = ((LLMGenerateNode) node)::apply;
        else if (node instanceof QueryDecomposeNode) fn = ((QueryDecomposeNode) node)::apply;
        else if (node instanceof EscalationNode) fn = ((EscalationNode) node)::apply;
        else if (node instanceof McpToolCallNode) fn = ((McpToolCallNode) node)::apply;
        else throw new IllegalArgumentException("Unsupported node type: " + node.getClass());

        graph.addNode(name, (OverAllState state, RunnableConfig config) -> {
            try {
                long start = System.currentTimeMillis();

                // 节点开始前检查客户端是否已断开，断开则短路返回，避免继续消耗算力
                java.util.concurrent.atomic.AtomicBoolean cancelledFlag =
                        (java.util.concurrent.atomic.AtomicBoolean) state.data()
                                .getOrDefault(StateKeys.CANCELLED, null);
                if (cancelledFlag != null && cancelledFlag.get()) {
                    log.warn("[Graph ✗] 节点跳过（客户端已断开）: {}", name);
                    Map<String, Object> cancelledResult = new HashMap<>();
                    cancelledResult.put(StateKeys.STEPS, "客户端已断开，节点跳过: " + name);
                    cancelledResult.put(StateKeys.STEP_TYPE, StateKeys.StepType.ERROR);
                    return CompletableFuture.completedFuture(cancelledResult);
                }

                log.info("[Graph ▶] 节点开始: {}", name);
                Map<String, Object> result = new HashMap<>(fn.apply(state.data()));
                result.putIfAbsent(StateKeys.STEP_TYPE, stepType);
                // 统一兜底：确保每个节点都有 STEPS，避免节点完成时没有 step 事件发出
                result.putIfAbsent(StateKeys.STEPS, "节点完成: " + name);
                log.info("[Graph ✓] 节点完成: {} ({}ms)", name, System.currentTimeMillis() - start);

                // 节点完成时立即回调（用于实时 SSE 推送）
                // 异步执行：避免 SSE 推送（含 backoff 背压等待）阻塞 Graph 节点链路
                if (nodeCallback != null) {
                    final Map<String, Object> callbackData = new HashMap<>(result);
                    callbackData.put("nodeName", name);
                    callbackData.put("timestamp", System.currentTimeMillis());
                    CompletableFuture.runAsync(() -> {
                        try {
                            nodeCallback.accept(callbackData);
                        } catch (Exception e) {
                            log.error("[Graph回调] 异步执行失败, nodeName={}, err={}",
                                    name, e.getMessage(), e);
                        }
                    }, graphCallbackExecutor);
                }

                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                log.error("[Graph ✗] 节点失败: {}", name, e);
                return CompletableFuture.failedFuture(e);
            }
        });
    }
}
