package com.xushu.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.entity.KnowledgeBase;

import java.util.List;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    BaseResponse listActiveKnowledgeBases();

    BaseResponse getKnowledgeBaseById(Long id);

    BaseResponse createKnowledgeBase(String name, String description, Long parentId);

    BaseResponse updateKnowledgeBase(Long id, String name, String description, String status);

    BaseResponse deleteKnowledgeBase(Long id);

    BaseResponse suggestKnowledgeBase(String content);
}
