package com.xushu.rag.graph.nodes;

import com.xushu.rag.graph.StateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 人工转接Node（Mock）
 * <p>记录转接日志，设置状态标记。Redis状态的 SET 和 divider 事件由 Controller 处理</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class EscalationNode {

    public Map<String, Object> apply(Map<String, Object> state) {
        String question = (String) state.getOrDefault(StateKeys.QUESTION, "");
        String emotion = (String) state.getOrDefault(StateKeys.EMOTION, "neutral");
        String reason = "negative".equals(emotion) ? "emotion_negative" : "manual";

        log.info("[人工转接] question='{}', reason={}, time={}",
                question.length() > 40 ? question.substring(0, 40) + "..." : question,
                reason, System.currentTimeMillis());

        return Map.of(
                StateKeys.ESCALATE, "true",
                StateKeys.STEPS, "已为您转接人工客服~"
        );
    }
}
