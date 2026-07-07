package com.xushu.rag.structured;

/**
 * LLM意图分类的结构化输出Record（用于BeanOutputConverter）
 * <p>由LLM返回JSON后自动反序列化为此类型，同时输出意图和情绪</p>
 *
 * @author Joseph
 */
public record IntentClassification(
        /** 意图类别：calculation / reference / operation / chitchat */
        String intent,
        /** 分类理由简述 */
        String reason,
        /** 置信度 0~1 */
        double confidence,
        /** 用户情绪：positive / neutral / negative（旧版LLM可能不返回，调用方兜底neutral） */
        String emotion,
        /**
         * 用户是否确认调用工具（仅当intent=operation时有意义）
         * true  = 用户明确说"好的""帮我查""执行""确认"等确认词
         * false = 用户只是在询问/描述需求，尚未确认
         */
        Boolean toolConfirm,
        /**
         * 目标MCP服务名（仅当intent=operation且有明确目标服务时填写）
         * 例如："amap-maps"（高德地图服务）、"ziniu-local-server"（紫牛本地服务）
         * 无法判断或不需要调用MCP时为空字符串或null
         */
        String targetMcpServer,
        /**
         * 答案形态：single_value / list / boolean / description / comparison
         * 用于 LLMGenerateNode 追加形态专属的输出结构约束。
         * 旧版LLM可能不返回，调用方兜底"description"
         */
        String answerType
) {}
