package com.xushu.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.entity.Document;

import java.util.List;

public interface DocumentService extends IService<Document> {

    BaseResponse listDocumentsByKbId(Long kbId);

    BaseResponse getDocumentById(Long id);

    BaseResponse createDocument(Document document);

    BaseResponse updateDocument(Long id, Document document);

    BaseResponse deleteDocument(Long id);

    String generateVersion(Long kbId, String originalName);

    BaseResponse getDocumentHistory(Long kbId, String originalName);

    BaseResponse getLatestDocuments(Long kbId);
}
