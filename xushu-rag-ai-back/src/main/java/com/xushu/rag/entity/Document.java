package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Date;

@TableName(value = "document")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String fileName;

    private String originalName;

    private String filePath;

    private String fileType;

    private String version;

    private String status;

    private String vectorId;

    private String url;

    private Date createTime;

    private Date updateTime;
}
