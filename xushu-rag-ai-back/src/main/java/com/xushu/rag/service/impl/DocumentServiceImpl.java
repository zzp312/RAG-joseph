package com.xushu.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.Document;
import com.xushu.rag.mapper.DocumentMapper;
import com.xushu.rag.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document>
        implements DocumentService {

    @Autowired
    private DocumentMapper documentMapper;

    @Override
    public BaseResponse listDocumentsByKbId(Long kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        return ResultUtils.success(documents);
    }

    @Override
    public BaseResponse getDocumentById(Long id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        }
        return ResultUtils.success(document);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse createDocument(Document document) {
        document.setCreateTime(new Date());
        document.setUpdateTime(new Date());
        int result = documentMapper.insert(document);
        return result > 0 ? ResultUtils.success(document) : ResultUtils.error(ErrorCode.OPERATION_ERROR, "创建失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse updateDocument(Long id, Document document) {
        Document existing = documentMapper.selectById(id);
        if (existing == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        }

        if (document.getFileName() != null) {
            existing.setFileName(document.getFileName());
        }
        if (document.getOriginalName() != null) {
            existing.setOriginalName(document.getOriginalName());
        }
        if (document.getFilePath() != null) {
            existing.setFilePath(document.getFilePath());
        }
        if (document.getFileType() != null) {
            existing.setFileType(document.getFileType());
        }
        if (document.getVersion() != null) {
            existing.setVersion(document.getVersion());
        }
        if (document.getStatus() != null) {
            existing.setStatus(document.getStatus());
        }
        if (document.getVectorId() != null) {
            existing.setVectorId(document.getVectorId());
        }
        if (document.getUrl() != null) {
            existing.setUrl(document.getUrl());
        }

        existing.setUpdateTime(new Date());
        int result = documentMapper.updateById(existing);
        return result > 0 ? ResultUtils.success(existing) : ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse deleteDocument(Long id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "文档不存在");
        }

        int result = documentMapper.deleteById(id);
        return result > 0 ? ResultUtils.success("删除成功") : ResultUtils.error(ErrorCode.OPERATION_ERROR, "删除失败");
    }

    @Override
    public String generateVersion(Long kbId, String originalName) {
        List<Document> existingDocuments = documentMapper.selectByKbIdAndOriginalName(kbId, originalName);
        
        long timestamp = System.currentTimeMillis();
        String version;
        
        if (existingDocuments.isEmpty()) {
            version = "v1.0." + timestamp;
        } else {
            int major = 1;
            int minor = 0;
            
            for (Document doc : existingDocuments) {
                String[] parts = doc.getVersion().split("\\.");
                if (parts.length >= 2) {
                    int currentMajor = Integer.parseInt(parts[0].substring(1));
                    int currentMinor = Integer.parseInt(parts[1]);
                    if (currentMajor > major) {
                        major = currentMajor;
                        minor = 0;
                    } else if (currentMajor == major) {
                        minor = Math.max(minor, currentMinor) + 1;
                    }
                }
            }
            
            version = "v" + major + "." + minor + "." + timestamp;
        }
        
        return version;
    }

    @Override
    public BaseResponse getDocumentHistory(Long kbId, String originalName) {
        List<Document> documents = documentMapper.selectByKbIdAndOriginalName(kbId, originalName);
        return ResultUtils.success(documents);
    }

    @Override
    public BaseResponse getLatestDocuments(Long kbId) {
        List<Document> documents = documentMapper.selectLatestVersionsByKbId(kbId);
        return ResultUtils.success(documents);
    }
}
