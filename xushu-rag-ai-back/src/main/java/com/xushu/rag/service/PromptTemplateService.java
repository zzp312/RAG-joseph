package com.xushu.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.entity.PromptTemplate;

import java.util.List;

public interface PromptTemplateService extends IService<PromptTemplate> {

    BaseResponse listTemplates();

    BaseResponse listTemplatesByKbId(Long kbId);

    BaseResponse getTemplateById(Long id);

    BaseResponse createTemplate(Long kbId, String name, String templateContent, Integer isDefault);

    BaseResponse updateTemplate(Long id, String name, String templateContent, String status, Integer isDefault);

    BaseResponse deleteTemplate(Long id);

    BaseResponse getTemplateForQuestion(Long kbId, String question);

    String renderTemplate(Long kbId, String question, String context);

    /**
     * 按分类和知识库ID查找模板（Graph Agent PromptRouteNode调用）
     * <p>匹配规则：template_type = category → 无命中则 is_default = 1</p>
     *
     * @param category 意图分类：calculation/reference/operation/chitchat/unknown
     * @param kbId     知识库ID（可为null）
     * @return 匹配的模板，未找到返回null
     * @author Joseph
     */
    PromptTemplate findByCategoryAndKbId(String category, Long kbId);
}
