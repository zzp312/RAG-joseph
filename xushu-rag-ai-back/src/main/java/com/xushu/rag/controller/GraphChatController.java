package com.xushu.rag.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.graph.RagGraphAgent;
import com.xushu.rag.graph.StateKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Graph Agent 问答控制器
 * <p>基于 Spring AI Alibaba Graph 工作流引擎的RAG问答接口</p>
 *
 * @author Joseph
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/chat")
@RequiredArgsConstructor
public class GraphChatController {

    private final RagGraphAgent ragGraphAgent;
    private final RedisTemplate<String, String> redisTemplate;
    private final ChatMemory chatMemory;

    /** Redis key前缀：用户检索指纹 */
    private static final String FINGERPRINT_KEY_PREFIX = "rag:kb_fingerprint:";

    /**
     * Graph Agent RAG问答（SSE流式）
     *
     * @param message   用户提问
     * @param kbIds     选中的知识库ID列表（可选）
     * @param sources   选中的文件列表（可选）
     * @param sessionId 会话标识（多标签页隔离）
     * @return SSE流
     */
    @PostMapping(value = "/rag-graph", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ragGraphChat(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "kbIds", required = false) List<Long> kbIds,
            @RequestParam(value = "sources", required = false) List<String> sources,
            @RequestParam(value = "sessionId", required = false, defaultValue = "default") String sessionId) {

        Long userId = BaseContext.getCurrentId();
        String conversationId = userId + "_" + sessionId;

        // 检索范围变化检测 → 清除旧记忆
        String currentFingerprint = (kbIds != null ? kbIds.toString() : "all")
                + "|" + (sources != null ? sources.toString() : "all");
        String redisKey = FINGERPRINT_KEY_PREFIX + conversationId;
        String previousFingerprint = redisTemplate.opsForValue().getAndSet(redisKey, currentFingerprint);
        if (previousFingerprint != null && !previousFingerprint.equals(currentFingerprint)) {
            chatMemory.clear(conversationId);
            log.info("[记忆清除] conversationId={}, {} → {}", conversationId, previousFingerprint, currentFingerprint);
        }

        // 构建初始 State
        Long effectiveKbId = (kbIds != null && !kbIds.isEmpty()) ? kbIds.get(0) : null;
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(StateKeys.QUESTION, message);
        initialState.put(StateKeys.CATEGORY, "unknown");
        initialState.put(StateKeys.KB_IDS, kbIds);
        initialState.put(StateKeys.SOURCES, sources);
        initialState.put(StateKeys.EFFECTIVE_KB_ID, effectiveKbId);
        initialState.put(StateKeys.CONVERSATION_ID, conversationId);
        initialState.put(StateKeys.DOCUMENTS, Collections.emptyList());
        initialState.put(StateKeys.CONTEXT, "");
        initialState.put(StateKeys.ANSWER, "");

        // 编译Graph并流式执行
        CompiledGraph compiledGraph;
        try {
            compiledGraph = ragGraphAgent.buildGraph();
        } catch (Exception e) {
            log.error("[GraphChat] Graph编译失败", e);
            return Flux.just("event: message\ndata: Graph编译失败: " + e.getMessage() + "\n\n");
        }

        return compiledGraph.stream(initialState)
                .map(output -> {
                    if (output instanceof StreamingOutput<?> streamOut) {
                        String nodeName = streamOut.node();
                        OverAllState nodeState = streamOut.state();
                        Map<String, Object> data = nodeState.data();

                        // 步骤消息
                        String steps = (String) data.getOrDefault(StateKeys.STEPS, "");

                        // 最终节点 → 发送答案
                        if ("llm_generate".equals(nodeName) || "__end__".equals(nodeName)) {
                            String answer = (String) data.getOrDefault(StateKeys.ANSWER, "");
                            if (!answer.isEmpty()) {
                                return formatSSE("message", answer);
                            }
                        }

                        // 中间节点 → 发送步骤事件（type从State中读取，由各Node声明）
                        if (steps != null && !steps.isEmpty()) {
                            String type = (String) data.getOrDefault(StateKeys.STEP_TYPE,
                                    StateKeys.StepType.THINKING);
                            return formatSSEStep(type, steps);
                        }
                    }
                    return ""; // 跳过无需展示的事件
                })
                .filter(s -> !s.isEmpty())
                .concatWith(Flux.just(formatSSE("done", "")));
    }

    /**
     * 格式化 SSE message 事件
     */
    private String formatSSE(String eventType, String content) {
        if ("done".equals(eventType)) {
            return "event: done\ndata: [DONE]\n\n";
        }
        return "event: message\ndata: " + content.replace("\n", "\\n") + "\n\n";
    }

    /**
     * 格式化 SSE step 事件
     */
    private String formatSSEStep(String type, String content) {
        return "event: step\ndata: {\"type\":\"" + type + "\",\"content\":\"" + escapeJson(content) + "\"}\n\n";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
