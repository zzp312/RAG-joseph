package com.xushu.rag.mcp;

import com.alibaba.fastjson.JSON;
import com.xushu.rag.entity.McpCallLog;
import com.xushu.rag.mapper.McpCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * MCP 工具调用审计日志记录器
 * <p>工具调用完成后异步写入 mcp_call_log 表，不阻塞工具返回。
 * arguments 和 result 截断到 2000 字符，防止超大字段打满表。</p>
 *
 * <p>使用 mcpAuditExecutor 线程池（core=2, max=4, queue=200, DiscardPolicy），
 * 队列满时丢弃日志，不影响业务。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class McpAuditLogger {

    private static final int MAX_TEXT_LENGTH = 2000;

    @Autowired
    private McpCallLogMapper mcpCallLogMapper;

    @Autowired
    @Qualifier("mcpAuditExecutor")
    private ThreadPoolExecutor mcpAuditExecutor;

    /**
     * 异步记录审计日志
     *
     * @param toolName          工具名称
     * @param callerSecretKeyId 调用方 SecretKey ID（来自鉴权 Filter）
     * @param arguments         调用参数（将 JSON 序列化并截断）
     * @param result            返回结果（将 JSON 序列化并截断）
     * @param durationMs        执行耗时（毫秒）
     * @param status            状态（SUCCESS/FAILED）
     * @param errorMessage      异常信息（无则 null）
     */
    public void logAsync(String toolName, Long callerSecretKeyId, Object arguments, Object result,
                         long durationMs, String status, String errorMessage) {
        String argsJson = toTruncatedJson(arguments);
        String resultJson = toTruncatedJson(result);

        mcpAuditExecutor.submit(() -> {
            try {
                McpCallLog logEntry = McpCallLog.builder()
                        .toolName(toolName)
                        .callerSecretKeyId(callerSecretKeyId)
                        .arguments(argsJson)
                        .result(resultJson)
                        .durationMs(durationMs)
                        .status(status)
                        .errorMessage(errorMessage)
                        .createTime(new Date())
                        .build();
                mcpCallLogMapper.insert(logEntry);
            } catch (Exception e) {
                log.warn("[McpAudit] 审计日志写入失败, tool={}, err={}", toolName, e.getMessage());
            }
        });
    }

    /**
     * 对象转 JSON 并截断到 2000 字符
     */
    private String toTruncatedJson(Object obj) {
        if (obj == null) {
            return null;
        }
        String json;
        try {
            json = JSON.toJSONString(obj);
        } catch (Exception e) {
            json = String.valueOf(obj);
        }
        if (json.length() > MAX_TEXT_LENGTH) {
            return json.substring(0, MAX_TEXT_LENGTH);
        }
        return json;
    }
}
