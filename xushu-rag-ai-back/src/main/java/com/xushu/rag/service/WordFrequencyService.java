package com.xushu.rag.service;

import com.xushu.rag.common.PageResult;
import com.xushu.rag.entity.WordFrequency;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.pojo.dto.WordFrequencyPageQueryDTO;

/**
* @author Xushu
* @description 针对表【word_frequency】的数据库操作Service
* @createDate 2025-03-06 15:56:07
*/
public interface WordFrequencyService extends IService<WordFrequency> {

    PageResult pageQuery(WordFrequencyPageQueryDTO queryDTO);
}
