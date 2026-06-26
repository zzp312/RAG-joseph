package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;

import java.util.Date;

/**
 * @TableName ali_oss_file
 */
@TableName(value ="ali_oss_file")
@Data
@Builder
public class AliOssFile {
    /**
     * 主键id
     */
    @TableId
    private Integer id;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 链接地址
     */
    private String url;

    /**
     * 该文件分割出的多段向量文本ID
     */
    private String vectorId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}