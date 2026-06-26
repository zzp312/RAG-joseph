package com.xushu.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档页面原文存储表（Small-to-Big检索回表）
 * <p>联合键: (source, version, page_number)，同名不同版本隔离</p>
 *
 * @author Joseph
 */
@TableName(value = "document_pages")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentPage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String source;

    /** 版本号，同名文件不同版本隔离 */
    private String version;

    private Integer pageNumber;

    private Integer segmentNumber;

    private String pageText;
}
