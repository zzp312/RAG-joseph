package com.xushu.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Server 专用线程池配置
 * <p>包含两个独立线程池，与业务接口线程池隔离，避免 MCP 调用暴增影响现有业务：</p>
 * <ul>
 *   <li>mcpUploadExecutor: 异步上传任务（大文件解析/切片/向量化）</li>
 *   <li>mcpAuditExecutor: 审计日志异步写入（不阻塞工具返回）</li>
 * </ul>
 *
 * @author Joseph
 */
@Slf4j
@Configuration
public class McpThreadPoolConfig {

    @Value("${mcp.server.upload.thread-pool.core-size:4}")
    private int uploadCoreSize;

    @Value("${mcp.server.upload.thread-pool.max-size:16}")
    private int uploadMaxSize;

    @Value("${mcp.server.upload.thread-pool.queue-capacity:100}")
    private int uploadQueueCapacity;

    /**
     * MCP 异步上传任务线程池
     * <p>拒绝策略 AbortPolicy：队列满时抛出 RejectedExecutionException，
     * 由调用方捕获后返回 503 "upload service busy"。</p>
     */
    @Bean("mcpUploadExecutor")
    public ThreadPoolExecutor mcpUploadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                uploadCoreSize,
                uploadMaxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(uploadQueueCapacity),
                new McpThreadFactory("mcp-upload"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        log.info("[McpUploadExecutor] 初始化完成, core={}, max={}, queue={}",
                uploadCoreSize, uploadMaxSize, uploadQueueCapacity);
        return executor;
    }

    /**
     * MCP 审计日志线程池
     * <p>小线程池即可，审计日志写入是轻量级 DB 操作。
     * 拒绝策略 DiscardPolicy：队列满时丢弃日志（审计日志不阻塞业务）。</p>
     */
    @Bean("mcpAuditExecutor")
    public ThreadPoolExecutor mcpAuditExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                4,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new McpThreadFactory("mcp-audit"),
                new ThreadPoolExecutor.DiscardPolicy()
        );
        log.info("[McpAuditExecutor] 初始化完成, core=2, max=4, queue=200");
        return executor;
    }

    /**
     * 自定义线程工厂：命名 + 守护线程 + 异常处理器
     */
    private static class McpThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;
        private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler =
                (t, e) -> log.error("[MCP] 线程未捕获异常, name={}, err={}", t.getName(), e.getMessage(), e);

        McpThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            return t;
        }
    }
}
