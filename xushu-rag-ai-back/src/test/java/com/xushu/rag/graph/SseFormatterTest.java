package com.xushu.rag.graph;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SseFormatter 单元测试
 * <p>验证 step 事件的 JSON 格式与 ts 字段正确性。</p>
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
}
