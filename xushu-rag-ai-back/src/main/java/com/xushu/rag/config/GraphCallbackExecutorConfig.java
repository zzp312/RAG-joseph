package com.xushu.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Graph 节点回调专用线程池
 * <p>用于异步执行 SSE 推送回调，避免回调阻塞 Graph 节点链路。
 * 与 ForkJoinPool.commonPool() 隔离，防止 SSE 推送任务影响其他 CompletableFuture 任务。</p>
 * <p><b>多服务器部署</b>：本线程池为单机本地线程池，每台服务器独立持有，
 * 不涉及跨机共享。配合负载均衡的 sticky session 模式使用即可。</p>
 *
 * @author Joseph
 */
@Slf4j
@Configuration
public class GraphCallbackExecutorConfig {

    /**
     * SSE 回调专用线程池
     * <p>核心线程数 = CPU 核数（IO 密集型场景可适当上调），
     * 队列容量 512（防止突发流量打满内存），拒绝策略为 CallerRuns（兜底同步执行）</p>
     */
    @Bean("graphCallbackExecutor")
    public ThreadPoolExecutor graphCallbackExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        int maxPoolSize = corePoolSize * 2;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                new GraphCallbackThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("[GraphCallbackExecutor] 初始化完成, core={}, max={}, queue=512",
                corePoolSize, maxPoolSize);
        return executor;
    }

    /**
     * 自定义线程工厂：命名 + 守护线程 + 异常处理器
     */
    private static class GraphCallbackThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
                (t, e) -> log.error("[GraphCallback] 线程未捕获异常, name={}, err={}", t.getName(), e.getMessage(), e);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "graph-callback-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            return t;
        }
    }
}
