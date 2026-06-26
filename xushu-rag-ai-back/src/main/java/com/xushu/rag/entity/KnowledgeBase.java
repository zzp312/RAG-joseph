package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName(value = "knowledge_base")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeBase {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long parentId;

    private String status;

    private Date createTime;

    private Date updateTime;
}
