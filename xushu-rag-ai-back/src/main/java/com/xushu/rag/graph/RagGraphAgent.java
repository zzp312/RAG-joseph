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
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * Graph Agent 构建器（Spring AI Alibaba Graph）
 * <p>
 * 工作流链路:
 * START → QuestionInput → IntentClassify → 条件边(category) →
 *   → PromptRoute → Retrieval → ContextBuild → LLMGenerate → END
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
    private final ChatModel chatModel;

    public RagGraphAgent(QuestionInputNode questionInputNode,
                         IntentClassifyNode intentClassifyNode,
                         PromptRouteNode promptRouteNode,
                         RetrievalNode retrievalNode,
                         ContextBuildNode contextBuildNode,
                         LLMGenerateNode llmGenerateNode,
                         QueryDecomposeNode queryDecomposeNode,
                         ChatModel chatModel) {
        this.questionInputNode = questionInputNode;
        this.intentClassifyNode = intentClassifyNode;
        this.promptRouteNode = promptRouteNode;
        this.retrievalNode = retrievalNode;
        this.contextBuildNode = contextBuildNode;
        this.llmGenerateNode = llmGenerateNode;
        this.queryDecomposeNode = queryDecomposeNode;
        this.chatModel = chatModel;
    }

    /**
     * 构建并编译 Graph Agent 工作流
     * <p>子类可覆盖 registerNodes() 和 wireEdges() 扩展新节点（Phase 2预留）</p>
     *
     * @return 可执行的 CompiledGraph
     */
    public CompiledGraph buildGraph() throws Exception {
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
        registerNodes(stateGraph);

        // 子步骤2：连接边（可扩展，支持条件分支）
        wireEdges(stateGraph);

        CompiledGraph compiledGraph = stateGraph.compile();
        log.info("[RagGraphAgent] Graph编译完成，节点数={}", ALL_STATE_KEYS.length);
        return compiledGraph;
    }

    /** 无需检索的意图分类（闲聊类，直接LLM回复） */
    private static final java.util.Set<String> SKIP_RETRIEVAL_CATEGORIES =
            java.util.Set.of("chitchat", "unknown");

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
            StateKeys.EMOTION, StateKeys.ESCALATE, StateKeys.MCP_RESULT, StateKeys.TOKEN_USAGE
    };

    /** 注册所有节点（Phase 2在此追加新节点） */
    protected void registerNodes(StateGraph stateGraph) throws GraphStateException {
        addNode(stateGraph, "question_input", questionInputNode, StateKeys.StepType.THINKING);
        addNode(stateGraph, "intent_classify", intentClassifyNode, StateKeys.StepType.THINKING);
        addNode(stateGraph, "query_decompose", queryDecomposeNode, StateKeys.StepType.THINKING);
        addNode(stateGraph, "prompt_route", promptRouteNode, StateKeys.StepType.THINKING);
        addNode(stateGraph, "retrieval", retrievalNode, StateKeys.StepType.TOOL);
        addNode(stateGraph, "context_build", contextBuildNode, StateKeys.StepType.THINKING);
        addNode(stateGraph, "llm_generate", llmGenerateNode, StateKeys.StepType.THINKING);
    }

    /** 连接所有边（comparison/aggregation → query_decompose → prompt_route） */
    protected void wireEdges(StateGraph stateGraph) throws GraphStateException {
        stateGraph.addEdge(START, "question_input");
        stateGraph.addEdge("question_input", "intent_classify");

        // 条件边：comparison/aggregation 走拆解节点，其他直连 prompt_route
        stateGraph.addConditionalEdges("intent_classify",
                state -> java.util.concurrent.CompletableFuture.completedFuture(
                        "comparison".equalsIgnoreCase(
                                (String) state.data().getOrDefault(StateKeys.CATEGORY, ""))
                        || "aggregation".equalsIgnoreCase(
                                (String) state.data().getOrDefault(StateKeys.CATEGORY, ""))
                                ? "query_decompose" : "prompt_route"),
                Map.of("query_decompose", "query_decompose", "prompt_route", "prompt_route"));

        stateGraph.addEdge("query_decompose", "prompt_route");

        // 条件边：chitchat 不需要检索，直接跳到 llm_generate
        stateGraph.addConditionalEdges("prompt_route",
                state -> java.util.concurrent.CompletableFuture.completedFuture(
                        isSkipRetrieval((String) state.data().getOrDefault(StateKeys.CATEGORY, ""))
                                ? "llm_generate" : "retrieval"),
                Map.of("retrieval", "retrieval", "llm_generate", "llm_generate"));

        stateGraph.addEdge("retrieval", "context_build");
        stateGraph.addEdge("context_build", "llm_generate");
        stateGraph.addEdge("llm_generate", END);
    }

    /** 通用节点注册：同步Node → AsyncNodeAction + stepType写入 */
    private <T> void addNode(StateGraph graph, String name, T node, String stepType)
            throws GraphStateException {
        java.util.function.Function<Map<String, Object>, Map<String, Object>> fn;
        if (node instanceof QuestionInputNode) fn = ((QuestionInputNode) node)::apply;
        else if (node instanceof IntentClassifyNode) fn = ((IntentClassifyNode) node)::apply;
        else if (node instanceof PromptRouteNode) fn = ((PromptRouteNode) node)::apply;
        else if (node instanceof RetrievalNode) fn = ((RetrievalNode) node)::apply;
        else if (node instanceof ContextBuildNode) fn = ((ContextBuildNode) node)::apply;
        else if (node instanceof LLMGenerateNode) fn = ((LLMGenerateNode) node)::apply;
        else if (node instanceof QueryDecomposeNode) fn = ((QueryDecomposeNode) node)::apply;
        else throw new IllegalArgumentException("Unsupported node type: " + node.getClass());

        graph.addNode(name, (OverAllState state, RunnableConfig config) -> {
            try {
                long start = System.currentTimeMillis();
                log.info("[Graph ▶] 节点开始: {}", name);
                Map<String, Object> result = new HashMap<>(fn.apply(state.data()));
                result.putIfAbsent(StateKeys.STEP_TYPE, stepType);
                log.info("[Graph ✓] 节点完成: {} ({}ms)", name, System.currentTimeMillis() - start);
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                log.error("[Graph ✗] 节点失败: {}", name, e);
                return CompletableFuture.failedFuture(e);
            }
        });
    }
}
