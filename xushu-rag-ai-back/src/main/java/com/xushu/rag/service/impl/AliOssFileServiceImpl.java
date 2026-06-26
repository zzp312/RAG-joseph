package com.xushu.rag.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.entity.AliOssFile;
import com.xushu.rag.entity.Document;
import com.xushu.rag.mapper.DocumentMapper;
import com.xushu.rag.pojo.dto.QueryFileDTO;
import com.xushu.rag.service.AliOssFileService;
import com.xushu.rag.mapper.AliOssFileMapper;
import com.xushu.rag.utils.AliOssUtil;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
* @author Xushu
* @description 针对表【ali_oss_file】的数据库操作Service实现
* @createDate 2025-02-08 20:51:33
*/
@Service
public class AliOssFileServiceImpl extends ServiceImpl<AliOssFileMapper, AliOssFile>
    implements AliOssFileService{

    @Autowired
    private AliOssFileMapper aliOssFileMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private AliOssUtil aliOssUtil;


    /**
     * 查询文件
     * @param request
     * @return
     */
    @Override
    public BaseResponse queryPage(QueryFileDTO request) {
        Page<Document> page = new Page<>(request.getPage(), request.getPageSize());
        LambdaQueryWrapper<Document> queryWrapper = new LambdaQueryWrapper<>();
        if (request.getKbId() != null) {
            queryWrapper.eq(Document::getKbId, request.getKbId());
        }
        if (request.getFileName() != null && !request.getFileName().isEmpty()) {
            queryWrapper.like(Document::getOriginalName, request.getFileName());
        }
        queryWrapper.orderByDesc(Document::getCreateTime);
        IPage<Document> fileList = documentMapper.selectPage(page, queryWrapper);
        return ResultUtils.success(fileList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse deleteFiles(List<Long> ids) {
        List<Document> documents = documentMapper.selectBatchIds(ids);
        if (documents.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请选择文件");
        }
        int count = documentMapper.deleteBatchIds(ids);
        if (count == 0) {
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "删除失败");
        }
        for (Document document : documents) {
            List<String> vectorIds = JSON.parseArray(document.getVectorId(), String.class);
            vectorStore.delete(vectorIds);
            aliOssUtil.deleteOss(document.getUrl());
        }

        return ResultUtils.success("成功删除"+ count + "个文件");
    }

    @Override
    public BaseResponse downloadFiles(List<Long> ids) {
        List<Document> documents = documentMapper.selectBatchIds(ids);
        if (documents.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请选择文件");
        }
        for (Document document : documents){
            String url = document.getUrl();
            String fileName = extractFileName(url);
            aliOssUtil.download(fileName);
        }
        return ResultUtils.success("下载成功");
    }
    public static String extractFileName(String url) {
        // 找到最后一个斜杠的位置
        int lastSlashIndex = url.lastIndexOf('/');
        if (lastSlashIndex == -1) {
            return url; // 如果没有找到斜杠，返回整个URL
        }
        // 从最后一个斜杠之后的部分截取
        return url.substring(lastSlashIndex + 1);
    }

}




