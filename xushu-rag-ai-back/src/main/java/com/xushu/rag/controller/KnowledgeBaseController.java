package com.xushu.rag.controller;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "KnowledgeBaseController", description = "知识库管理接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb")
public class KnowledgeBaseController {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "list", description = "获取所有启用的知识库")
    @GetMapping("/list")
    public BaseResponse listKnowledgeBases() {
        return knowledgeBaseService.listActiveKnowledgeBases();
    }

    @Operation(summary = "get", description = "根据ID获取知识库")
    @GetMapping("/{id}")
    public BaseResponse getKnowledgeBase(@PathVariable Long id) {
        return knowledgeBaseService.getKnowledgeBaseById(id);
    }

    @Operation(summary = "create", description = "创建知识库")
    @PostMapping("/create")
    public BaseResponse createKnowledgeBase(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Long parentId) {
        return knowledgeBaseService.createKnowledgeBase(name, description, parentId);
    }

    @Operation(summary = "update", description = "更新知识库")
    @PutMapping("/{id}")
    public BaseResponse updateKnowledgeBase(
            @PathVariable Long id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String status) {
        return knowledgeBaseService.updateKnowledgeBase(id, name, description, status);
    }

    @Operation(summary = "delete", description = "删除知识库")
    @DeleteMapping("/{id}")
    public BaseResponse deleteKnowledgeBase(@PathVariable Long id) {
        return knowledgeBaseService.deleteKnowledgeBase(id);
    }

    @Operation(summary = "suggest", description = "根据内容建议知识库分类")
    @PostMapping("/suggest")
    public BaseResponse suggestKnowledgeBase(@RequestBody String content) {
        return knowledgeBaseService.suggestKnowledgeBase(content);
    }
}
