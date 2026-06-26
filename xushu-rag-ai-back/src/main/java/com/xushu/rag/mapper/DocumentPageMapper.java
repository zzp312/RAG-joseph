package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.DocumentPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author Joseph
 */
@Mapper
public interface DocumentPageMapper extends BaseMapper<DocumentPage> {

    List<DocumentPage> selectBySourceVersionAndPages(@Param("source") String source,
                                                      @Param("version") String version,
                                                      @Param("pageNumbers") List<Integer> pageNumbers);
}
