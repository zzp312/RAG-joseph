package com.xushu.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("SELECT * FROM knowledge_base WHERE status = 'ACTIVE' ORDER BY create_time DESC")
    List<KnowledgeBase> selectActiveKnowledgeBases();

    @Select("SELECT kb.id, kb.name, kb.description, kb.create_time, COUNT(d.id) as doc_count " +
            "FROM knowledge_base kb LEFT JOIN document d ON kb.id = d.kb_id " +
            "WHERE kb.status = 'ACTIVE' " +
            "GROUP BY kb.id, kb.name, kb.description, kb.create_time " +
            "ORDER BY kb.create_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> selectActiveWithDocCountPaged(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM knowledge_base WHERE status = 'ACTIVE'")
    int countActive();

    @Select("SELECT * FROM knowledge_base WHERE name = #{name} AND status = 'ACTIVE'")
    KnowledgeBase selectByName(@Param("name") String name);

    @Select("SELECT * FROM knowledge_base WHERE parent_id = #{parentId} AND status = 'ACTIVE'")
    List<KnowledgeBase> selectByParentId(@Param("parentId") Long parentId);
}
