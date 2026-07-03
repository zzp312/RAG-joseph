package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    @Select("SELECT * FROM prompt_template WHERE kb_id = #{kbId} AND status = 'ACTIVE'")
    List<PromptTemplate> selectByKbId(@Param("kbId") Long kbId);

    @Select("SELECT * FROM prompt_template WHERE kb_id IS NULL AND is_default = 1 AND status = 'ACTIVE'")
    PromptTemplate selectDefaultTemplate();

    @Select("SELECT * FROM prompt_template WHERE status = 'ACTIVE' ORDER BY create_time DESC")
    List<PromptTemplate> selectActiveTemplates();

    @Select("SELECT * FROM prompt_template WHERE kb_id = #{kbId} AND is_default = 1 AND status = 'ACTIVE'")
    PromptTemplate selectDefaultByKbId(@Param("kbId") Long kbId);

    /**
     * 按类型和知识库ID查询模板（Graph Agent PromptRouteNode 调用）
     *
     * @author Joseph
     */
    @Select("SELECT * FROM prompt_template WHERE template_type = #{templateType} AND kb_id = #{kbId} AND status = 'ACTIVE' LIMIT 1")
    PromptTemplate selectByTypeAndKbId(@Param("templateType") String templateType, @Param("kbId") Long kbId);

    /**
     * 按类型查询全局默认模板（无kb_id绑定）
     *
     * @author Joseph
     */
    @Select("SELECT * FROM prompt_template WHERE template_type = #{templateType} AND kb_id IS NULL AND status = 'ACTIVE' LIMIT 1")
    PromptTemplate selectDefaultByType(@Param("templateType") String templateType);
}
