package com.xushu.rag.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * 通义千问VL多模态图片描述工具
 * <p>
 * 通过DashScope MultiModalConversation API对图片进行语义描述，
 * 描述文本后续将向量化存入Milvus，支持基于语义的图片检索。
 * </p>
 * <p>
 * 使用模型：qwen-vl-plus（兼顾精度与成本）
 * </p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class ImageDescriber {

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dashscope.multimodal.model:qwen-vl-plus}")
    private String model;

    @Value("${dashscope.multimodal.max-tokens:400}")
    private int maxTokens;

    /**
     * 对图片生成中文语义描述
     *
     * @param imagePath 图片文件路径
     * @return 中文描述文本，失败时返回null
     */
    public String describeImage(String imagePath) {
        if (dashscopeApiKey == null || dashscopeApiKey.isEmpty()) {
            log.warn("DashScope API Key未配置，跳过图片描述");
            return null;
        }

        try {
            Path path = Paths.get(imagePath);
            if (!Files.exists(path)) {
                log.warn("图片文件不存在: {}", imagePath);
                return null;
            }

            byte[] imageBytes = Files.readAllBytes(path);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            String imageUrl = "data:image/png;base64," + base64Image;

            // 构建请求
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            JSONObject input = new JSONObject();
            JSONArray messages = new JSONArray();

            // system message
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            JSONArray sysContent = new JSONArray();
            JSONObject sysText = new JSONObject();
            sysText.put("text", "你是一个专业的文档图片分析助手，请用中文简洁准确地描述图片中的内容，包括图表类型、关键数据、主要趋势和文字信息。");
            sysContent.add(sysText);
            sysMsg.put("content", sysContent);
            messages.add(sysMsg);

            // user message with image
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            JSONArray userContent = new JSONArray();
            JSONObject imgItem = new JSONObject();
            imgItem.put("image", imageUrl);
            userContent.add(imgItem);
            JSONObject textItem = new JSONObject();
            textItem.put("text", "请描述这张图片的内容");
            userContent.add(textItem);
            userMsg.put("content", userContent);
            messages.add(userMsg);

            input.put("messages", messages);
            requestBody.put("input", input);

            JSONObject params = new JSONObject();
            params.put("max_tokens", maxTokens);
            requestBody.put("parameters", params);

            // 发送HTTP请求
            String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + dashscopeApiKey)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
                    .build();

            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                JSONObject respJson = JSON.parseObject(response.body());
                JSONObject output = respJson.getJSONObject("output");
                if (output != null) {
                    JSONArray choices = output.getJSONArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject message = choice.getJSONObject("message");
                        if (message != null) {
                            JSONArray contentArr = message.getJSONArray("content");
                            if (contentArr != null && !contentArr.isEmpty()) {
                                String description = contentArr.getJSONObject(0).getString("text");
                                if (description != null && !description.isEmpty()) {
                                    log.info("图片描述生成成功: path={}, desc_length={}", imagePath, description.length());
                                    return description;
                                }
                            }
                        }
                    }
                }
                log.warn("DashScope返回格式异常: {}", response.body());
            } else {
                log.warn("DashScope API调用失败: status={}, body={}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("图片描述生成失败: path={}, error={}", imagePath, e.getMessage(), e);
        }

        return null;
    }
}
