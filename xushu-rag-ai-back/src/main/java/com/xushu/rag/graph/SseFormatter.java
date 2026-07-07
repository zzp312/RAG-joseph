package com.xushu.rag.graph;

import com.alibaba.fastjson.JSONObject;
import org.springframework.http.codec.ServerSentEvent;

/**
 * SSE 事件格式化工具
 * <p>将后端事件封装为标准的 {@link ServerSentEvent}，避免 Spring 对 {@code Flux<String>}
 * 进行二次 SSE 包装导致前端收到原始的 {@code event:\n data:...} 文本。</p>
 *
 * @author Joseph
 */
public final class SseFormatter {

    private SseFormatter() {
    }

    /**
     * 生成 message 事件
     *
     * @param content 真实答案文本
     * @return SSE 事件
     */
    public static ServerSentEvent<String> message(String content) {
        return ServerSentEvent.<String>builder()
                .event("message")
                .data(content)
                .build();
    }

    /**
     * 生成 step 事件
     *
     * @param type    步骤类型：thinking / tool / error
     * @param content 步骤描述
     * @param ts      节点完成时刻的时间戳(毫秒,服务端时钟),为 null 则不输出该字段
     * @return SSE 事件，data 为 JSON
     */
    public static ServerSentEvent<String> step(String type, String content, Long ts) {
        JSONObject json = new JSONObject();
        json.put("type", type == null || type.isEmpty() ? StateKeys.StepType.THINKING : type);
        json.put("content", content == null ? "" : content);
        if (ts != null) {
            json.put("ts", ts);
        }
        return ServerSentEvent.<String>builder()
                .event("step")
                .data(json.toJSONString())
                .build();
    }

    /**
     * 生成 done 事件，标识流结束
     *
     * @return SSE 事件
     */
    public static ServerSentEvent<String> done() {
        return ServerSentEvent.<String>builder()
                .event("done")
                .data("[DONE]")
                .build();
    }

    /**
     * 生成 thinking 事件（CoT 深度思考过程）
     * <p>前端解析后渲染为可折叠的"💭 深度思考"区块</p>
     *
     * @param cotContent CoT 推理文本
     * @return SSE 事件，data 为 JSON
     */
    public static ServerSentEvent<String> thinking(String cotContent) {
        JSONObject json = new JSONObject();
        json.put("content", cotContent == null ? "" : cotContent);
        return ServerSentEvent.<String>builder()
                .event("thinking")
                .data(json.toJSONString())
                .build();
    }

    /**
     * 生成 divider 分界线事件（人工接管/回AI）
     *
     * @param type    分界线类型：human_start / human_end
     * @param content 分界线文本，如"人工客服已接入"
     * @return SSE 事件，data 为 JSON
     */
    public static ServerSentEvent<String> divider(String type, String content) {
        JSONObject json = new JSONObject();
        json.put("type", type == null ? "divider" : type);
        json.put("content", content == null ? "" : content);
        return ServerSentEvent.<String>builder()
                .event("divider")
                .data(json.toJSONString())
                .build();
    }
}
