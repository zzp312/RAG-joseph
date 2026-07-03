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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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

        // 客户端取消标志：由 SSE doOnCancel 置为 true，各节点检查此标志后短路返回
        AtomicBoolean cancelled = new AtomicBoolean(false);
        initialState.put(StateKeys.CANCELLED, cancelled);

        // 创建有界 Sinks.Many（避免慢客户端导致无界积压）用于解耦节点执行和 SSE 发送
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast()
                .onBackpressureBuffer(new LinkedBlockingQueue<>(256));

        long requestStart = System.currentTimeMillis();
        log.info("[GraphChat] 收到请求, conversationId={}, message={}, kbIds={}, sources={}",
                conversationId,
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                kbIds, sources);

        // 异步执行 Graph
        CompletableFuture.runAsync(() -> {
            try {
                // 编译Graph（带回调）
                // 注意：回调在 graphCallbackExecutor 线程池异步执行，
                // 不阻塞 Graph 节点链路；客户端断开后通过 cancelled 标志提前 return
                CompiledGraph compiledGraph = ragGraphAgent.buildGraphWithCallback(callbackData -> {
                    try {
                        // 客户端已断开，不再推送
                        if (cancelled.get()) {
                            return;
                        }

                        String nodeName = (String) callbackData.get("nodeName");
                        Long nodeTs = (Long) callbackData.get("timestamp");
                        String steps = (String) callbackData.getOrDefault(StateKeys.STEPS, "");

                        log.info("[SSE推送] thread={}, nodeName={}, timestamp={}",
                                Thread.currentThread().getName(), nodeName, nodeTs);

                        // 最终节点 → 发送答案
                        if ("llm_generate".equals(nodeName)) {
                            String answer = (String) callbackData.getOrDefault(StateKeys.ANSWER, "");
                            if (!answer.isEmpty()) {
                                tryEmitOrLog(sink, SseFormatter.message(answer));
                            }
                            return;
                        }

                        // 中间节点 → 发送步骤事件
                        if (steps != null && !steps.isEmpty()) {
                            String type = (String) callbackData.getOrDefault(StateKeys.STEP_TYPE,
                                    StateKeys.StepType.THINKING);
                            tryEmitOrLog(sink, SseFormatter.step(type, steps, nodeTs));
                        }
                    } catch (Exception e) {
                        log.error("[SSE推送失败]", e);
                    }
                });

                // 执行Graph（不使用stream，直接invoke）
                compiledGraph.invoke(initialState);

                long elapsed = System.currentTimeMillis() - requestStart;
                if (cancelled.get()) {
                    log.warn("[GraphChat] Graph 执行结束（客户端已断开）, conversationId={}, 耗时={}ms",
                            conversationId, elapsed);
                } else {
                    log.info("[GraphChat] Graph 执行结束, conversationId={}, 耗时={}ms",
                            conversationId, elapsed);
                    sink.tryEmitNext(SseFormatter.done());
                    sink.tryEmitComplete();
                }

            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("[GraphChat] 执行失败, conversationId={}", conversationId, e);
                    sink.tryEmitError(e);
                } else {
                    log.warn("[GraphChat] 执行异常（客户端已断开，忽略）, conversationId={}, err={}",
                            conversationId, e.getMessage());
                }
            }
        });

        return sink.asFlux().doOnCancel(() -> {
            long elapsed = System.currentTimeMillis() - requestStart;
            cancelled.set(true);
            log.warn("[SSE] 客户端断开连接，已设置取消标志, conversationId={}, 已耗时={}ms",
                    conversationId, elapsed);
        });
    }

    /**
     * 尝试推送 SSE 事件
     * <p>缓冲区满时通过指数退避重试（100ms → 200ms → 400ms...），累计最多约 2 秒，
     * 避免直接丢弃事件；若超时后仍未成功，则记录日志并丢弃，防止无限阻塞 Graph 执行线程。</p>
     * <p>注意：本方法在 CompletableFuture 线程中调用，背压等待会暂时阻塞当前节点的回调，
     * 但不影响其他请求线程。建议同步配合回调异步化（P1）使用。</p>
     *
     * @author Joseph
     */
    private static void tryEmitOrLog(Sinks.Many<ServerSentEvent<String>> sink, ServerSentEvent<String> event) {
        // 先尝试快速发送（无等待）
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (!result.isFailure()) {
            return;
        }

        // 缓冲区满：指数退避重试，累计上限 2s
        long maxWaitMs = 2000;
        long waited = 0;
        long sleepMs = 100;
        while (waited < maxWaitMs) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            waited += sleepMs;
            result = sink.tryEmitNext(event);
            if (!result.isFailure()) {
                log.info("[SSE] 背压等待后推送成功, waited={}ms, event={}", waited, event.event());
                return;
            }
            sleepMs = Math.min(sleepMs * 2, 500); // 单次最长 500ms
        }

        log.warn("[SSE] 背压等待超时，丢弃事件, waited={}ms, result={}, event={}",
                waited, result, event.event());
    }
}
