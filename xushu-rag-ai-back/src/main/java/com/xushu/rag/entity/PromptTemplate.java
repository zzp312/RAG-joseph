package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName(value = "prompt_template")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PromptTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String name;

    private String templateContent;

    private String variables;

    private String status;

    private Integer isDefault;

    private Date createTime;

    private Date updateTime;
}
