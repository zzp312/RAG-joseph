package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;

import java.util.Date;

/**
 * 结构化数据实体类
 */
@Data
@Builder
@TableName(value = "structured_data")
public class StructuredData {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long fileId;
    
    private String tableName;
    
    private String fieldInfo;
    
    private Integer rowCount;
    
    private Boolean tableCreated;
    
    private Date createTime;
    
    private Date updateTime;
}