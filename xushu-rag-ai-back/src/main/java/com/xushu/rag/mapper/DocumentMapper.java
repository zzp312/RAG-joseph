package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    @Select("SELECT * FROM document WHERE kb_id = #{kbId} ORDER BY create_time DESC")
    List<Document> selectByKbId(@Param("kbId") Long kbId);

    @Select("SELECT * FROM document WHERE kb_id = #{kbId} AND original_name = #{originalName} ORDER BY version DESC")
    List<Document> selectByKbIdAndOriginalName(@Param("kbId") Long kbId, @Param("originalName") String originalName);

    @Select("SELECT MAX(version) FROM document WHERE kb_id = #{kbId} AND original_name = #{originalName}")
    String selectLatestVersion(@Param("kbId") Long kbId, @Param("originalName") String originalName);

    @Select("SELECT * FROM document WHERE id IN (SELECT MAX(id) FROM document WHERE kb_id = #{kbId} GROUP BY original_name)")
    List<Document> selectLatestVersionsByKbId(@Param("kbId") Long kbId);

    @Select("SELECT COUNT(*) FROM document WHERE kb_id = #{kbId}")
    int countByKbId(@Param("kbId") Long kbId);
}
