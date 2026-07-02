package com.xushu.rag.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.graph.RagGraphAgent;
import com.xushu.rag.graph.SseFormatter;
import com.xushu.rag.graph.StateKeys;
import com.xushu.rag.graph.nodes.HybridSearchTestNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.CompletableFuture;

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
    private final HybridSearchTestNode hybridSearchTestNode;

    /** Redis key前缀：用户检索指纹 */
    private static final String FINGERPRINT_KEY_PREFIX = "rag:kb_fingerprint:";

    /** Milvus BM25 混合检索兼容性检测 */
    @PostMapping(value = "/bm25-test", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> bm25Test() {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = hybridSearchTestNode.apply(new HashMap<>());
            return (String) result.getOrDefault("answer", "{}");
        });
    }

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
    public Flux<ServerSentEvent<String>> ragGraphChat(
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

        // 创建 Sinks.Many 用于解耦节点执行和 SSE 发送
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 异步执行 Graph
        CompletableFuture.runAsync(() -> {
            try {
                // 编译Graph（带回调）
                CompiledGraph compiledGraph = ragGraphAgent.buildGraphWithCallback(callbackData -> {
                    try {
                        String nodeName = (String) callbackData.get("nodeName");
                        Long nodeTs = (Long) callbackData.get("timestamp");
                        String steps = (String) callbackData.getOrDefault(StateKeys.STEPS, "");

                        log.info("[SSE推送] nodeName={}, timestamp={}", nodeName, nodeTs);

                        // 最终节点 → 发送答案
                        if ("llm_generate".equals(nodeName)) {
                            String answer = (String) callbackData.getOrDefault(StateKeys.ANSWER, "");
                            if (!answer.isEmpty()) {
                                sink.tryEmitNext(SseFormatter.message(answer));
                            }
                            return;
                        }

                        // 中间节点 → 发送步骤事件
                        if (steps != null && !steps.isEmpty()) {
                            String type = (String) callbackData.getOrDefault(StateKeys.STEP_TYPE,
                                    StateKeys.StepType.THINKING);
                            sink.tryEmitNext(SseFormatter.step(type, steps, nodeTs));
                        }
                    } catch (Exception e) {
                        log.error("[SSE推送失败]", e);
                    }
                });

                // 执行Graph（不使用stream，直接invoke）
                compiledGraph.invoke(initialState);

                // 发送结束信号
                sink.tryEmitNext(SseFormatter.done());
                sink.tryEmitComplete();

            } catch (Exception e) {
                log.error("[GraphChat] 执行失败", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }
}
