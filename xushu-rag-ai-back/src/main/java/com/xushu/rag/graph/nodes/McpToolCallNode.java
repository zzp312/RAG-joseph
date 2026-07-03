package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP工具调用Node（Mock）
 * <p>用户确认执行操作后，mock调用MCP工具。真实对接时替换为McpClient调用</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class McpToolCallNode {

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");

        log.info("[McpToolCall Mock] 模拟执行MCP工具, question='{}'", 
                question.length() > 50 ? question.substring(0, 50) + "..." : question);

        String mockResult = "操作已提交（mock），后续将对接真实系统";

        return Map.of(
                StateKeys.MCP_RESULT, mockResult,
                StateKeys.ANSWER, mockResult,
                StateKeys.STEPS, "MCP工具调用完成（mock）"
        );
    }
}
