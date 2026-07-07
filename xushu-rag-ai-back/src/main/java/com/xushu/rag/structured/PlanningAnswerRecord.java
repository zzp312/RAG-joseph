package com.xushu.rag.structured;

/**
 * 规划类意图的结构化答案（用于 BeanOutputConverter）
 * <p>轻量 CoT：只需规划步骤 + 注意事项，不强求每步都有上下文出处。</p>
 * <p>禁止捏造具体数据（距离/价格/时间等），由策略层校验。</p>
 *
 * @author Joseph
 */
public record PlanningAnswerRecord(
        /**
         * 规划步骤列表，每步一句话描述做什么
         */
        String planSteps,

        /**
         * 注意事项：约束条件、需要用户确认的信息、材料不足之处的说明
         */
        String considerations,

        /**
         * 面向用户的最终方案/回答
         */
        String finalAnswer
) {}
