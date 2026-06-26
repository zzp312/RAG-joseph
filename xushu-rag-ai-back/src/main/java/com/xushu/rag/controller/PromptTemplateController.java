package com.xushu.rag.controller;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.service.PromptTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "PromptTemplateController", description = "提示词模板管理接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/template")
public class PromptTemplateController {

    @Autowired
    private PromptTemplateService promptTemplateService;

    @Operation(summary = "list", description = "获取所有启用的模板")
    @GetMapping("/list")
    public BaseResponse listTemplates() {
        return promptTemplateService.listTemplates();
    }

    @Operation(summary = "listByKb", description = "根据知识库ID获取模板")
    @GetMapping("/list/{kbId}")
    public BaseResponse listTemplatesByKbId(@PathVariable Long kbId) {
        return promptTemplateService.listTemplatesByKbId(kbId);
    }

    @Operation(summary = "get", description = "根据ID获取模板")
    @GetMapping("/{id}")
    public BaseResponse getTemplate(@PathVariable Long id) {
        return promptTemplateService.getTemplateById(id);
    }

    @Operation(summary = "create", description = "创建模板")
    @PostMapping("/create")
    public BaseResponse createTemplate(
            @RequestParam(required = false) Long kbId,
            @RequestParam String name,
            @RequestParam String templateContent,
            @RequestParam(required = false, defaultValue = "0") Integer isDefault) {
        return promptTemplateService.createTemplate(kbId, name, templateContent, isDefault);
    }

    @Operation(summary = "update", description = "更新模板")
    @PutMapping("/{id}")
    public BaseResponse updateTemplate(
            @PathVariable Long id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String templateContent,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer isDefault) {
        return promptTemplateService.updateTemplate(id, name, templateContent, status, isDefault);
    }

    @Operation(summary = "delete", description = "删除模板")
    @DeleteMapping("/{id}")
    public BaseResponse deleteTemplate(@PathVariable Long id) {
        return promptTemplateService.deleteTemplate(id);
    }

    @Operation(summary = "getForQuestion", description = "根据问题获取合适的模板")
    @PostMapping("/get-for-question")
    public BaseResponse getTemplateForQuestion(
            @RequestParam(required = false) Long kbId,
            @RequestBody String question) {
        return promptTemplateService.getTemplateForQuestion(kbId, question);
    }
}
