package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提问输入标准化Node
 * <p>将用户输入写入State，类别初始化为unknown</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class QuestionInputNode {

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        if (question == null || question.trim().isEmpty()) {
            question = "你好";
        }

        log.info("[QuestionInput] question={}, kbIds={}, sources={}",
                question.length() > 50 ? question.substring(0, 50) + "..." : question,
                state.get(StateKeys.KB_IDS), state.get(StateKeys.SOURCES));

        // 透传关键字段，避免State合并时丢失
        Map<String, Object> result = new HashMap<>();
        result.put(StateKeys.QUESTION, question);
        result.put(StateKeys.CATEGORY, "unknown");
        result.put(StateKeys.KB_IDS, state.get(StateKeys.KB_IDS));
        result.put(StateKeys.SOURCES, state.get(StateKeys.SOURCES));
        result.put(StateKeys.EFFECTIVE_KB_ID, state.get(StateKeys.EFFECTIVE_KB_ID));
        result.put(StateKeys.CONVERSATION_ID, state.get(StateKeys.CONVERSATION_ID));
        return result;
    }
}
