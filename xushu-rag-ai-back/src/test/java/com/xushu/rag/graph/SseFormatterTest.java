package com.xushu.rag.graph;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SseFormatter 单元测试
 * <p>验证 step / message / divider 事件的格式正确性。</p>
 *
 * @author Joseph
 */
class SseFormatterTest {

    @Test
    void step_shouldIncludeTsField() {
        ServerSentEvent<String> event = SseFormatter.step("thinking", "检索知识库", 1700000000000L);
        assertEquals("step", event.event());

        String data = event.data();
        assertNotNull(data);
        assertTrue(data.contains("\"type\":\"thinking\""));
        assertTrue(data.contains("\"content\":\"检索知识库\""));
        assertTrue(data.contains("\"ts\":1700000000000"));
    }

    @Test
    void step_shouldOmitTsWhenNull() {
        ServerSentEvent<String> event = SseFormatter.step("tool", "调用工具", null);
        String data = event.data();
        assertNotNull(data);
        assertTrue(data.contains("\"type\":\"tool\""));
        assertFalse(data.contains("\"ts\""));
    }

    // ==================== divider 测试 ====================

    @Test
    void divider_humanStart_shouldReturnCorrectEvent() {
        ServerSentEvent<String> event = SseFormatter.divider("human_start", "人工客服已接入");
        assertEquals("divider", event.event());

        String data = event.data();
        assertNotNull(data);
        assertTrue(data.contains("\"type\":\"human_start\""));
        assertTrue(data.contains("\"content\":\"人工客服已接入\""));
    }

    @Test
    void divider_humanEnd_shouldReturnCorrectEvent() {
        ServerSentEvent<String> event = SseFormatter.divider("human_end", "AI已恢复服务");
        assertEquals("divider", event.event());

        String data = event.data();
        assertNotNull(data);
        assertTrue(data.contains("\"type\":\"human_end\""));
        assertTrue(data.contains("\"content\":\"AI已恢复服务\""));
    }
}
