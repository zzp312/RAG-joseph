package com.xushu.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xushu.rag.entity.DocumentPage;

import java.util.List;
import java.util.Map;

/**
 * @author Joseph
 */
public interface DocumentPageService extends IService<DocumentPage> {

    void batchSave(List<DocumentPage> pages);

    /**
     * 按(source, version, pages)回表查原文
     */
    Map<String, String> getPageTexts(String source, String version, List<Integer> pageNumbers);
}
