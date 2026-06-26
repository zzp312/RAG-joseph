package com.xushu.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xushu.rag.entity.DocumentPage;
import com.xushu.rag.mapper.DocumentPageMapper;
import com.xushu.rag.service.DocumentPageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @author Joseph
 */
@Slf4j
@Service
public class DocumentPageServiceImpl extends ServiceImpl<DocumentPageMapper, DocumentPage>
        implements DocumentPageService {

    @Autowired
    private DocumentPageMapper documentPageMapper;

    @Override
    public void batchSave(List<DocumentPage> pages) {
        if (pages != null && !pages.isEmpty()) {
            saveBatch(pages);
        }
    }

    @Override
    public Map<String, String> getPageTexts(String source, String version, List<Integer> pageNumbers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            return result;
        }
        List<DocumentPage> segments = documentPageMapper.selectBySourceVersionAndPages(source, version, pageNumbers);

        // 回表查询日志：记录命中的数据源
        log.info("[Small-to-Big回表] source={}, version={}, pages={}, 命中{}行",
                source, version, pageNumbers, segments.size());
        for (DocumentPage seg : segments) {
            log.info("  └ document_pages id={}, page={}, segment={}, text_len={}",
                    seg.getId(), seg.getPageNumber(), seg.getSegmentNumber(),
                    seg.getPageText() != null ? seg.getPageText().length() : 0);
        }

        for (DocumentPage seg : segments) {
            String key = source + "_" + seg.getPageNumber();
            result.merge(key, seg.getPageText(), (old, add) -> old + add);
        }
        return result;
    }

    /** 超长页面分段，段间重叠 */
    public static List<DocumentPage> splitPage(String source, String version, int pageNumber,
                                                String fullText, int maxChars, int overlapChars) {
        List<DocumentPage> segments = new ArrayList<>();
        if (fullText == null || fullText.isEmpty()) return segments;

        int total = fullText.length();
        if (total <= maxChars) {
            segments.add(DocumentPage.builder()
                    .source(source).version(version)
                    .pageNumber(pageNumber).segmentNumber(0).pageText(fullText).build());
            return segments;
        }

        int start = 0, segNum = 0;
        while (start < total) {
            int end = Math.min(start + maxChars, total);
            segments.add(DocumentPage.builder()
                    .source(source).version(version)
                    .pageNumber(pageNumber).segmentNumber(segNum)
                    .pageText(fullText.substring(start, end)).build());
            segNum++;
            if (end >= total) break;
            start = Math.max(start + 1, end - overlapChars);
        }
        return segments;
    }
}
