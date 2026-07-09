package com.xushu.rag.mcp;

import com.xushu.rag.entity.McpCallLog;
import com.xushu.rag.mapper.McpCallLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * McpAuditLogger 单元测试
 *
 * @author Joseph
 */
@ExtendWith(MockitoExtension.class)
class McpAuditLoggerTest {

    @Mock
    private McpCallLogMapper mcpCallLogMapper;

    @Mock
    private ThreadPoolExecutor mcpAuditExecutor;

    @InjectMocks
    private McpAuditLogger mcpAuditLogger;

    @SuppressWarnings("unchecked")
    private void mockSyncSubmit() {
        when(mcpAuditExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return (Future<Void>) null;
        });
    }

    @Test
    void logAsync_withNormalArgs_shouldInsertAuditLog() {
        mockSyncSubmit();
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        Map<String, Object> result = new HashMap<>();
        result.put("total", 5);

        mcpAuditLogger.logAsync("search_knowledge", 1L, args, result, 150L, "SUCCESS", null);

        ArgumentCaptor<McpCallLog> captor = ArgumentCaptor.forClass(McpCallLog.class);
        verify(mcpCallLogMapper).insert(captor.capture());

        McpCallLog log = captor.getValue();
        assertEquals("search_knowledge", log.getToolName());
        assertEquals(1L, log.getCallerSecretKeyId());
        assertEquals(150L, log.getDurationMs());
        assertEquals("SUCCESS", log.getStatus());
        assertNull(log.getErrorMessage());
        assertNotNull(log.getCreateTime());
        assertTrue(log.getArguments().contains("test"));
        assertTrue(log.getResult().contains("5"));
    }

    @Test
    void logAsync_withOversizedArgs_shouldTruncateTo2000Chars() {
        mockSyncSubmit();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("a");
        }
        Map<String, Object> args = new HashMap<>();
        args.put("text", sb.toString());

        mcpAuditLogger.logAsync("upload_kb_file", 2L, args, null, 5000L, "SUCCESS", null);

        ArgumentCaptor<McpCallLog> captor = ArgumentCaptor.forClass(McpCallLog.class);
        verify(mcpCallLogMapper).insert(captor.capture());

        McpCallLog log = captor.getValue();
        assertNotNull(log.getArguments());
        assertTrue(log.getArguments().length() <= 2000,
                "arguments should be truncated to <= 2000 chars, actual=" + log.getArguments().length());
        assertNull(log.getResult());
    }

    @Test
    void logAsync_withFailedStatus_shouldRecordErrorMessage() {
        mockSyncSubmit();
        mcpAuditLogger.logAsync("list_knowledge_bases", null, null, null, 30L, "FAILED", "db connection lost");

        ArgumentCaptor<McpCallLog> captor = ArgumentCaptor.forClass(McpCallLog.class);
        verify(mcpCallLogMapper).insert(captor.capture());

        McpCallLog log = captor.getValue();
        assertEquals("FAILED", log.getStatus());
        assertEquals("db connection lost", log.getErrorMessage());
        assertNull(log.getCallerSecretKeyId());
    }

    @Test
    void logAsync_whenDbInsertFails_shouldNotThrowException() {
        mockSyncSubmit();
        doThrow(new RuntimeException("db down")).when(mcpCallLogMapper).insert(any(McpCallLog.class));

        assertDoesNotThrow(() ->
                mcpAuditLogger.logAsync("search_knowledge", 1L, null, null, 100L, "SUCCESS", null));
    }

    @Test
    void logAsync_shouldSubmitToExecutor() {
        mcpAuditLogger.logAsync("search_knowledge", 1L, null, null, 100L, "SUCCESS", null);

        verify(mcpAuditExecutor).submit(any(Runnable.class));
        verifyNoInteractions(mcpCallLogMapper);
    }
}
