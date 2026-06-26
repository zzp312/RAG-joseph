package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.StructuredData;
import org.apache.ibatis.annotations.Mapper;

/**
 * 结构化数据 Mapper 接口
 */
@Mapper
public interface StructuredDataMapper extends BaseMapper<StructuredData> {
}