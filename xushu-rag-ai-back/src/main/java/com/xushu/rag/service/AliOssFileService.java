package com.xushu.rag.service;

import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.entity.AliOssFile;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.pojo.dto.QueryFileDTO;

import java.util.List;

/**
* @author Xushu
* @description 针对表【ali_oss_file】的数据库操作Service
* @createDate 2025-02-08 20:51:33
*/
public interface AliOssFileService extends IService<AliOssFile> {

    BaseResponse queryPage(QueryFileDTO request);

    BaseResponse deleteFiles(List<Long> ids);

    BaseResponse downloadFiles(List<Long> ids);
}
