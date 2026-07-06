package com.xushu.rag.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    @Value("${dashscope.multimodal.max-tokens:500}")
    private int maxTokens;

    /**
     * 对图片生成中文语义描述
     *
     * @param imagePath 图片文件路径
     * @return 格式为 "[主题标签]\n标签内容\n[图片描述]\n描述内容" 的结构化文本，失败时返回null
     */
    public String describeImage(String imagePath) {
        return describeImage(imagePath, null, null);
    }

    /**
     * 对图片生成中文语义描述（带文档上下文，提升检索召回率）
     *
     * @param imagePath       图片文件路径
     * @param documentName    所属文档名称（如"洛阳旅游景点全解析.docx"），可为null
     * @param kbName          所属知识库名称，可为null
     * @return 结构化文本，失败时返回null
     */
    public String describeImage(String imagePath, String documentName, String kbName) {
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

            // system message（优化：要求输出结构化标签+描述，标签用于检索召回）
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            JSONArray sysContent = new JSONArray();
            JSONObject sysText = new JSONObject();
            sysText.put("text",
                    "你是一个专业的图片内容分析助手。请按以下严格格式描述图片内容：\n\n"
                    + "[主题标签]\n"
                    + "用3-8个逗号分隔的关键词标签概括图片主题，包括：地点、类别、主要对象、适用场景等。"
                    + "例如：洛阳,美食,锅贴,西工饭庄,小吃,特色餐饮\n\n"
                    + "[图片描述]\n"
                    + "用中文详细描述图片中的具体内容，包括场景、主体对象、文字信息、氛围等。"
                    + "描述应保持客观准确，便于后续检索和理解。\n\n"
                    + "输出示例：\n"
                    + "[主题标签]\n"
                    + "洛阳,美食,锅贴,西工饭庄,小吃,传统餐饮\n"
                    + "[图片描述]\n"
                    + "西工饭庄小街锅贴的店铺外观，红色招牌上写着...");
            sysContent.add(sysText);
            sysMsg.put("content", sysContent);
            messages.add(sysMsg);

            // user message with image + 文档上下文
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            JSONArray userContent = new JSONArray();
            JSONObject imgItem = new JSONObject();
            imgItem.put("image", imageUrl);
            userContent.add(imgItem);
            JSONObject textItem = new JSONObject();
            StringBuilder userText = new StringBuilder("请按格式描述这张图片。");
            if (documentName != null || kbName != null) {
                userText.append("该图片来自文档《").append(documentName != null ? documentName : "未知")
                        .append("》（知识库：").append(kbName != null ? kbName : "默认").append("），");
                userText.append("请在主题标签中体现文档的领域信息。");
            }
            textItem.put("text", userText.toString());
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
