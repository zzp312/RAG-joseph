package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("SELECT * FROM knowledge_base WHERE status = 'ACTIVE' ORDER BY create_time DESC")
    List<KnowledgeBase> selectActiveKnowledgeBases();

    @Select("SELECT * FROM knowledge_base WHERE name = #{name} AND status = 'ACTIVE'")
    KnowledgeBase selectByName(@Param("name") String name);

    @Select("SELECT * FROM knowledge_base WHERE parent_id = #{parentId} AND status = 'ACTIVE'")
    List<KnowledgeBase> selectByParentId(@Param("parentId") Long parentId);
}
