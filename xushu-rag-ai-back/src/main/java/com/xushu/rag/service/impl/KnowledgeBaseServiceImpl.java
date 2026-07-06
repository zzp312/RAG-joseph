package com.xushu.rag.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.KnowledgeBase;
import com.xushu.rag.entity.PromptTemplate;
import com.xushu.rag.mapper.KnowledgeBaseMapper;
import com.xushu.rag.service.KnowledgeBaseService;
import com.xushu.rag.service.PromptTemplateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Override
    public BaseResponse listActiveKnowledgeBases() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectActiveKnowledgeBases();
        return ResultUtils.success(knowledgeBases);
    }

    @Override
    public BaseResponse getKnowledgeBaseById(Long id) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        }
        return ResultUtils.success(knowledgeBase);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse createKnowledgeBase(String name, String description, Long parentId) {
        KnowledgeBase existing = knowledgeBaseMapper.selectByName(name);
        if (existing != null) {
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "知识库名称已存在");
        }

        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .name(name)
                .description(description)
                .parentId(parentId)
                .status("ACTIVE")
                .createTime(new Date())
                .updateTime(new Date())
                .build();

        int result = knowledgeBaseMapper.insert(knowledgeBase);
        if (result > 0) {
            String defaultTemplate = "你是{" + knowledgeBase.getName() + "}知识库的智能助手，请根据以下上下文回答问题：\n\n上下文：{context}\n\n问题：{question}\n\n请仅基于上下文回答，不要引入外部知识。";
            promptTemplateService.createTemplate(knowledgeBase.getId(), knowledgeBase.getName() + "默认模板", defaultTemplate, 1);
            return ResultUtils.success(knowledgeBase);
        }
        return ResultUtils.error(ErrorCode.OPERATION_ERROR, "创建失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updateKnowledgeBase(Long id, String name, String description, String status) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        }

        if (name != null && !name.isEmpty()) {
            KnowledgeBase existing = knowledgeBaseMapper.selectByName(name);
            if (existing != null && !existing.getId().equals(id)) {
                return ResultUtils.error(ErrorCode.OPERATION_ERROR, "知识库名称已存在");
            }
            knowledgeBase.setName(name);
        }

        if (description != null) {
            knowledgeBase.setDescription(description);
        }

        if (status != null) {
            knowledgeBase.setStatus(status);
        }

        knowledgeBase.setUpdateTime(new Date());
        int result = knowledgeBaseMapper.updateById(knowledgeBase);
        return result > 0 ? ResultUtils.success(knowledgeBase) : ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse deleteKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(id);
        if (knowledgeBase == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        }

        knowledgeBase.setStatus("INACTIVE");
        knowledgeBase.setUpdateTime(new Date());
        int result = knowledgeBaseMapper.updateById(knowledgeBase);
        return result > 0 ? ResultUtils.success("删除成功") : ResultUtils.error(ErrorCode.OPERATION_ERROR, "删除失败");
    }

    @Override
    public BaseResponse suggestKnowledgeBase(String content) {
        List<KnowledgeBase> existingBases = knowledgeBaseMapper.selectActiveKnowledgeBases();
        if (existingBases.isEmpty()) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("suggested_kb_name", "通用知识库");
            fallback.put("confidence", 1.0);
            fallback.put("is_new", true);
            return ResultUtils.success(fallback);
        }

        StringBuilder existingNames = new StringBuilder();
        for (KnowledgeBase kb : existingBases) {
            existingNames.append(kb.getName()).append("、");
        }

        String prompt = """
                请分析以下文档内容，判断其最适合归类到哪个知识库。
                现有知识库列表：%s
                文档内容摘要：%s
                如果现有知识库都不合适，请给出一个广义的新知识库名称（2-4个字），便于后续类似文件自动路由。
                请严格按照JSON格式返回，不要输出任何其他文字：{"suggested_kb_name": "知识库名称", "confidence": 0-1, "is_new": true/false}
                """;

        String formattedPrompt = String.format(prompt, existingNames.toString(), content.length() > 2000 ? content.substring(0, 2000) : content);

        try {
            ChatClient chatClient = ChatClient.builder(chatModel).build();
            String response = chatClient.prompt()
                    .user(formattedPrompt)
                    .call()
                    .content();

            // 从LLM响应中提取JSON（兼容markdown代码块和文本+JSON混合输出）
            String jsonStr = extractJsonFromLLMResponse(response);
            if (jsonStr == null) {
                log.warn("无法从LLM响应中提取JSON，响应内容: " + (response != null ? response.substring(0, Math.min(response.length(), 200)) : "null"));
                throw new RuntimeException("LLM返回格式异常，无法解析JSON");
            }

            JSONObject jsonResponse = JSON.parseObject(jsonStr);
            String suggestedName = jsonResponse.getString("suggested_kb_name");
            double confidence = jsonResponse.getDoubleValue("confidence");
            boolean isNew = jsonResponse.getBooleanValue("is_new");

            Map<String, Object> result = new HashMap<>();
            result.put("suggested_kb_name", suggestedName);
            result.put("confidence", confidence);
            result.put("is_new", isNew);

            if (!isNew) {
                KnowledgeBase kb = knowledgeBaseMapper.selectByName(suggestedName);
                if (kb != null) {
                    result.put("kb_id", kb.getId());
                }
            }

            return ResultUtils.success(result);
        } catch (Exception e) {
            log.error("LLM分类失败", e);
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("suggested_kb_name", "通用知识库");
            fallback.put("confidence", 1.0);
            fallback.put("is_new", true);
            return ResultUtils.success(fallback);
        }
    }

    /**
     * 从LLM响应中提取JSON字符串
     * <p>兼容以下格式：
     * <ul>
     *   <li>纯JSON: {"key": "value"}</li>
     *   <li>Markdown代码块: ```json\n{...}\n```</li>
     *   <li>文本+JSON混合: 一些解释文字... {"key": "value"}</li>
     * </ul>
     * </p>
     *
     * @param response LLM原始响应
     * @return 提取出的JSON字符串，如果无法提取则返回null
     */
    private String extractJsonFromLLMResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        // 1. 优先提取markdown代码块中的JSON
        int codeBlockStart = response.indexOf("```json");
        if (codeBlockStart >= 0) {
            int jsonStart = response.indexOf('\n', codeBlockStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        // 无lang标注的代码块
        codeBlockStart = response.indexOf("```");
        if (codeBlockStart >= 0) {
            int jsonStart = response.indexOf('\n', codeBlockStart);
            int jsonEnd = response.indexOf("```", jsonStart + 1);
            if (jsonStart > 0 && jsonEnd > jsonStart) {
                String candidate = response.substring(jsonStart, jsonEnd).trim();
                if (candidate.startsWith("{")) {
                    return candidate;
                }
            }
        }

        // 2. 从文本中提取JSON对象（找第一个{和最后一个}配对）
        int firstBrace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1).trim();
        }

        return null;
    }
}
