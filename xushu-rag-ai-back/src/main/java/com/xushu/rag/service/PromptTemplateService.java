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
}
