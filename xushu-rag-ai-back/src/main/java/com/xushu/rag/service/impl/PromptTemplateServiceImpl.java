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
import com.xushu.rag.mapper.PromptTemplateMapper;
import com.xushu.rag.service.PromptTemplateService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PromptTemplateServiceImpl extends ServiceImpl<PromptTemplateMapper, PromptTemplate>
        implements PromptTemplateService {

    @Autowired
    private PromptTemplateMapper promptTemplateMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private ChatModel chatModel;

    @Override
    public BaseResponse listTemplates() {
        List<PromptTemplate> templates = promptTemplateMapper.selectActiveTemplates();
        return ResultUtils.success(templates);
    }

    @Override
    public BaseResponse listTemplatesByKbId(Long kbId) {
        List<PromptTemplate> templates = promptTemplateMapper.selectByKbId(kbId);
        return ResultUtils.success(templates);
    }

    @Override
    public BaseResponse getTemplateById(Long id) {
        PromptTemplate template = promptTemplateMapper.selectById(id);
        if (template == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "模板不存在");
        }
        return ResultUtils.success(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse createTemplate(Long kbId, String name, String templateContent, Integer isDefault) {
        PromptTemplate existing = promptTemplateMapper.selectDefaultByKbId(kbId);
        if (existing != null && isDefault != null && isDefault == 1) {
            existing.setIsDefault(0);
            promptTemplateMapper.updateById(existing);
        }

        List<String> variables = extractVariables(templateContent);

        PromptTemplate template = PromptTemplate.builder()
                .kbId(kbId)
                .name(name)
                .templateContent(templateContent)
                .variables(JSON.toJSONString(variables))
                .status("ACTIVE")
                .isDefault(isDefault != null ? isDefault : 0)
                .createTime(new Date())
                .updateTime(new Date())
                .build();

        int result = promptTemplateMapper.insert(template);
        return result > 0 ? ResultUtils.success(template) : ResultUtils.error(ErrorCode.OPERATION_ERROR, "创建失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updateTemplate(Long id, String name, String templateContent, String status, Integer isDefault) {
        PromptTemplate template = promptTemplateMapper.selectById(id);
        if (template == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "模板不存在");
        }

        if (isDefault != null && isDefault == 1) {
            PromptTemplate existing = promptTemplateMapper.selectDefaultByKbId(template.getKbId());
            if (existing != null && !existing.getId().equals(id)) {
                existing.setIsDefault(0);
                promptTemplateMapper.updateById(existing);
            }
        }

        if (name != null) {
            template.setName(name);
        }
        if (templateContent != null) {
            template.setTemplateContent(templateContent);
            template.setVariables(JSON.toJSONString(extractVariables(templateContent)));
        }
        if (status != null) {
            template.setStatus(status);
        }
        if (isDefault != null) {
            template.setIsDefault(isDefault);
        }

        template.setUpdateTime(new Date());
        int result = promptTemplateMapper.updateById(template);
        return result > 0 ? ResultUtils.success(template) : ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse deleteTemplate(Long id) {
        PromptTemplate template = promptTemplateMapper.selectById(id);
        if (template == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "模板不存在");
        }

        int result = promptTemplateMapper.deleteById(id);
        return result > 0 ? ResultUtils.success("删除成功") : ResultUtils.error(ErrorCode.OPERATION_ERROR, "删除失败");
    }

    @Override
    public BaseResponse getTemplateForQuestion(Long kbId, String question) {
        if (kbId != null) {
            PromptTemplate template = promptTemplateMapper.selectDefaultByKbId(kbId);
            if (template != null) {
                return ResultUtils.success(template);
            }
        }

        PromptTemplate defaultTemplate = promptTemplateMapper.selectDefaultTemplate();
        if (defaultTemplate != null) {
            return ResultUtils.success(defaultTemplate);
        }

        return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "未找到可用的提示词模板");
    }

    @Override
    public String renderTemplate(Long kbId, String question, String context) {
        PromptTemplate template = null;

        if (kbId != null) {
            template = promptTemplateMapper.selectDefaultByKbId(kbId);
        }

        if (template == null) {
            template = promptTemplateMapper.selectDefaultTemplate();
        }

        if (template == null) {
            return "请根据以下上下文回答问题：\n\n上下文：" + context + "\n\n问题：" + question;
        }

        String kbName = "知识库";
        if (kbId != null) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null) {
                kbName = kb.getName();
            }
        }

        return template.getTemplateContent()
                .replace("{context}", context)
                .replace("{question}", question)
                .replace("{kb_name}", kbName);
    }

    /**
     * 按分类和知识库ID查找模板（Graph Agent PromptRouteNode 调用）
     * <p>匹配优先级：category+kbId精准匹配 → kbId默认模板 → 全局默认模板</p>
     *
     * @author Joseph
     */
    @Override
    public PromptTemplate findByCategoryAndKbId(String category, Long kbId) {
        // 1. 精准匹配：template_type = category + kb_id = kbId
        PromptTemplate template = promptTemplateMapper.selectByTypeAndKbId(category, kbId);
        if (template != null) {
            return template;
        }

        // 2. 兜底：kbId 下的默认模板
        if (kbId != null) {
            template = promptTemplateMapper.selectDefaultByKbId(kbId);
            if (template != null) {
                return template;
            }
        }

        // 3. 全局默认模板
        return promptTemplateMapper.selectDefaultByType("default");
    }

    private List<String> extractVariables(String templateContent) {
        List<String> variables = new ArrayList<>();
        int start = 0;
        while (true) {
            int openBrace = templateContent.indexOf("{", start);
            if (openBrace == -1) break;
            int closeBrace = templateContent.indexOf("}", openBrace);
            if (closeBrace == -1) break;
            String variable = templateContent.substring(openBrace + 1, closeBrace);
            if (!variables.contains(variable)) {
                variables.add(variable);
            }
            start = closeBrace + 1;
        }
        return variables;
    }
}
