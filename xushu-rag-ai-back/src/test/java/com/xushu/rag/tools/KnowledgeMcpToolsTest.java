//package com.xushu.rag.tools;
//
//import com.xushu.rag.entity.KnowledgeBase;
//import com.xushu.rag.entity.McpUploadTask;
//import com.xushu.rag.mcp.McpAuditLogger;
//import com.xushu.rag.mapper.DocumentMapper;
//import com.xushu.rag.mapper.KnowledgeBaseMapper;
//import com.xushu.rag.mapper.McpUploadTaskMapper;
//import com.xushu.rag.pojo.dto.UploadResult;
//import com.xushu.rag.service.HybridSearchService;
//import com.xushu.rag.service.KnowledgeUploadService;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.ai.document.Document;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Base64;
//import java.util.Collections;
//import java.util.Date;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ThreadPoolExecutor;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.when;
//
///**
// * KnowledgeMcpTools 单元测试
// * <p>验证 MCP Server 暴露的 4 个知识库工具的行为正确性。</p>
// *
// * @author Joseph
// */
//@ExtendWith(MockitoExtension.class)
//class KnowledgeMcpToolsTest {
//
//    @Mock
//    private KnowledgeBaseMapper knowledgeBaseMapper;
//
//    @Mock
//    private DocumentMapper documentMapper;
//
//    @Mock
//    private HybridSearchService hybridSearchService;
//
//    @Mock
//    private McpUploadTaskMapper mcpUploadTaskMapper;
//
//    @Mock
//    private KnowledgeUploadService knowledgeUploadService;
//
//    @Mock
//    private McpAuditLogger mcpAuditLogger;
//
//    @Mock
//    private ThreadPoolExecutor mcpUploadExecutor;
//
//    @InjectMocks
//    private KnowledgeMcpTools knowledgeMcpTools;
//
//    // ==================== list_knowledge_bases ====================
//
//    @Test
//    void listKnowledgeBases_withDefaultPagination_shouldReturnFirstPageWith20PageSize() {
//        KnowledgeBase kb1 = KnowledgeBase.builder()
//                .id(1L).name("通用知识库").description("默认通用").status("ACTIVE")
//                .createTime(new Date()).build();
//        KnowledgeBase kb2 = KnowledgeBase.builder()
//                .id(2L).name("技术文档库").description("技术文档").status("ACTIVE")
//                .createTime(new Date()).build();
//        when(knowledgeBaseMapper.selectActiveKnowledgeBases())
//                .thenReturn(Arrays.asList(kb1, kb2));
//        when(documentMapper.countByKbId(1L)).thenReturn(5);
//        when(documentMapper.countByKbId(2L)).thenReturn(3);
//
//        Map<String, Object> result = knowledgeMcpTools.listKnowledgeBases(null, null);
//
//        assertEquals(2, result.get("total"));
//        assertEquals(1, result.get("page"));
//        assertEquals(20, result.get("pageSize"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> kbs = (List<Map<String, Object>>) result.get("kbs");
//        assertEquals(2, kbs.size());
//
//        Map<String, Object> first = kbs.get(0);
//        assertEquals(1L, first.get("kbId"));
//        assertEquals("通用知识库", first.get("kbName"));
//        assertEquals(5, first.get("docCount"));
//        assertEquals("默认通用", first.get("description"));
//        assertNotNull(first.get("createTime"));
//
//        Map<String, Object> second = kbs.get(1);
//        assertEquals(2L, second.get("kbId"));
//        assertEquals(3, second.get("docCount"));
//    }
//
//    @Test
//    void listKnowledgeBases_withNoActiveKb_shouldReturnEmptyList() {
//        when(knowledgeBaseMapper.selectActiveKnowledgeBases())
//                .thenReturn(Collections.emptyList());
//
//        Map<String, Object> result = knowledgeMcpTools.listKnowledgeBases(null, null);
//
//        assertEquals(0, result.get("total"));
//        assertEquals(1, result.get("page"));
//        assertEquals(20, result.get("pageSize"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> kbs = (List<Map<String, Object>>) result.get("kbs");
//        assertTrue(kbs.isEmpty());
//    }
//
//    @Test
//    void listKnowledgeBases_withCustomPagination_shouldReturnRequestedPage() {
//        List<KnowledgeBase> kbs = new ArrayList<>();
//        for (int i = 1; i <= 25; i++) {
//            kbs.add(KnowledgeBase.builder()
//                    .id((long) i).name("KB" + i).description("desc" + i).status("ACTIVE")
//                    .createTime(new Date()).build());
//        }
//        when(knowledgeBaseMapper.selectActiveKnowledgeBases()).thenReturn(kbs);
//        // 只 stub 第 2 页（11-20）的 countByKbId，避免严格模式 UnnecessaryStubbingException
//        for (int i = 11; i <= 20; i++) {
//            when(documentMapper.countByKbId((long) i)).thenReturn(i);
//        }
//
//        Map<String, Object> result = knowledgeMcpTools.listKnowledgeBases(2, 10);
//
//        assertEquals(25, result.get("total"));
//        assertEquals(2, result.get("page"));
//        assertEquals(10, result.get("pageSize"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> pagedKbs = (List<Map<String, Object>>) result.get("kbs");
//        assertEquals(10, pagedKbs.size());
//        // 第 2 页应该是第 11-20 个
//        assertEquals(11L, pagedKbs.get(0).get("kbId"));
//        assertEquals("KB11", pagedKbs.get(0).get("kbName"));
//        assertEquals(20L, pagedKbs.get(9).get("kbId"));
//    }
//
//    @Test
//    void listKnowledgeBases_withPageBeyondRange_shouldReturnEmptyList() {
//        List<KnowledgeBase> kbs = new ArrayList<>();
//        for (int i = 1; i <= 5; i++) {
//            kbs.add(KnowledgeBase.builder()
//                    .id((long) i).name("KB" + i).status("ACTIVE").createTime(new Date()).build());
//        }
//        when(knowledgeBaseMapper.selectActiveKnowledgeBases()).thenReturn(kbs);
//
//        Map<String, Object> result = knowledgeMcpTools.listKnowledgeBases(3, 10);
//
//        assertEquals(5, result.get("total"));
//        assertEquals(3, result.get("page"));
//        assertEquals(10, result.get("pageSize"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> pagedKbs = (List<Map<String, Object>>) result.get("kbs");
//        assertTrue(pagedKbs.isEmpty());
//    }
//
//    // ==================== search_knowledge ====================
//
//    @Test
//    void searchKnowledge_withDefaultTopK_shouldReturnChunksWithScoreAndMetadata() {
//        Map<String, Object> meta1 = new HashMap<>();
//        meta1.put("source", "ml_basics.pdf");
//        meta1.put("kb_id", 1L);
//        meta1.put("kb_name", "机器学习库");
//        meta1.put("version", "v1.0.123");
//        meta1.put("page", 5);
//        meta1.put("chunk_type", "TEXT");
//        Document doc1 = Document.builder().text("朴素贝叶斯是一种基于贝叶斯定理的分类算法").metadata(meta1).score(0.95).build();
//
//        Map<String, Object> meta2 = new HashMap<>();
//        meta2.put("source", "naive_bayes.docx");
//        meta2.put("kb_id", 1L);
//        meta2.put("kb_name", "机器学习库");
//        meta2.put("version", "v1.0.456");
//        meta2.put("page", 2);
//        meta2.put("chunk_type", "TEXT");
//        Document doc2 = Document.builder().text("贝叶斯分类器假设特征之间相互独立").metadata(meta2).score(0.82).build();
//
//        when(hybridSearchService.search(eq("什么是朴素贝叶斯"), eq(5), isNull()))
//                .thenReturn(Arrays.asList(doc1, doc2));
//
//        Map<String, Object> result = knowledgeMcpTools.searchKnowledge("什么是朴素贝叶斯", null, null);
//
//        assertEquals(2, result.get("total"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
//        assertEquals(2, chunks.size());
//
//        Map<String, Object> first = chunks.get(0);
//        assertEquals("朴素贝叶斯是一种基于贝叶斯定理的分类算法", first.get("content"));
//        assertEquals("ml_basics.pdf", first.get("source"));
//        assertEquals(0.95, first.get("score"));
//        assertEquals(5, first.get("page"));
//        assertEquals("机器学习库", first.get("kbName"));
//        assertEquals("v1.0.123", first.get("version"));
//    }
//
//    @Test
//    void searchKnowledge_withKbIdFilter_shouldPassFilterExpressionToSearchService() {
//        Document doc = Document.builder()
//                .text("结果内容")
//                .metadata(new HashMap<>())
//                .score(0.9)
//                .build();
//        when(hybridSearchService.search(eq("查询"), anyInt(), eq("kb_id in [3]")))
//                .thenReturn(Collections.singletonList(doc));
//
//        Map<String, Object> result = knowledgeMcpTools.searchKnowledge("查询", 3L, null);
//
//        assertEquals(1, result.get("total"));
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
//        assertEquals(1, chunks.size());
//    }
//
//    @Test
//    void searchKnowledge_withTopKExceedingMax_shouldBeCappedToMaxTopK() {
//        when(hybridSearchService.search(eq("查询"), eq(20), isNull()))
//                .thenReturn(Collections.emptyList());
//
//        Map<String, Object> result = knowledgeMcpTools.searchKnowledge("查询", null, 100);
//
//        assertEquals(0, result.get("total"));
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
//        assertTrue(chunks.isEmpty());
//    }
//
//    @Test
//    void searchKnowledge_withCustomTopK_shouldUseProvidedValue() {
//        Document doc = Document.builder()
//                .text("内容")
//                .metadata(new HashMap<>())
//                .score(0.8)
//                .build();
//        when(hybridSearchService.search(eq("查询"), eq(15), isNull()))
//                .thenReturn(Collections.singletonList(doc));
//
//        Map<String, Object> result = knowledgeMcpTools.searchKnowledge("查询", null, 15);
//
//        assertEquals(1, result.get("total"));
//    }
//
//    // ==================== get_upload_status ====================
//
//    @Test
//    void getUploadStatus_withSuccessTask_shouldReturnSuccessInfo() {
//        Date completeTime = new Date();
//        McpUploadTask task = McpUploadTask.builder()
//                .taskId("task-123").fileName("doc.pdf").kbId(1L).kbName("ML库")
//                .status("SUCCESS").progress(100)
//                .vectorIds("[\"v1\",\"v2\",\"v3\"]")
//                .completeTime(completeTime).build();
//        when(mcpUploadTaskMapper.selectByTaskId("task-123")).thenReturn(task);
//
//        com.xushu.rag.entity.Document docEntity = com.xushu.rag.entity.Document.builder()
//                .version("v1.0.123").build();
//        when(documentMapper.selectByKbIdAndOriginalName(1L, "doc.pdf"))
//                .thenReturn(Collections.singletonList(docEntity));
//
//        Map<String, Object> result = knowledgeMcpTools.getUploadStatus("task-123");
//
//        assertEquals("task-123", result.get("taskId"));
//        assertEquals("SUCCESS", result.get("status"));
//        assertEquals("doc.pdf", result.get("fileName"));
//        assertEquals(1L, result.get("kbId"));
//        assertEquals("ML库", result.get("kbName"));
//        assertEquals("v1.0.123", result.get("version"));
//        assertEquals(3, result.get("chunkCount"));
//        assertEquals(completeTime, result.get("completeTime"));
//    }
//
//    @Test
//    void getUploadStatus_withProcessingTask_shouldReturnStageAndProgress() {
//        McpUploadTask task = McpUploadTask.builder()
//                .taskId("task-456").fileName("big.pdf").kbId(2L)
//                .status("PROCESSING").stage("EMBEDDING").progress(60).build();
//        when(mcpUploadTaskMapper.selectByTaskId("task-456")).thenReturn(task);
//
//        Map<String, Object> result = knowledgeMcpTools.getUploadStatus("task-456");
//
//        assertEquals("task-456", result.get("taskId"));
//        assertEquals("PROCESSING", result.get("status"));
//        assertEquals("EMBEDDING", result.get("stage"));
//        assertEquals(60, result.get("progress"));
//        assertEquals("big.pdf", result.get("fileName"));
//        assertEquals(2L, result.get("kbId"));
//    }
//
//    @Test
//    void getUploadStatus_withFailedTask_shouldReturnErrorInfo() {
//        Date completeTime = new Date();
//        McpUploadTask task = McpUploadTask.builder()
//                .taskId("task-789").fileName("corrupt.pdf")
//                .status("FAILED").stage("PARSING")
//                .errorMessage("文件格式损坏").errorStage("PARSING")
//                .completeTime(completeTime).build();
//        when(mcpUploadTaskMapper.selectByTaskId("task-789")).thenReturn(task);
//
//        Map<String, Object> result = knowledgeMcpTools.getUploadStatus("task-789");
//
//        assertEquals("task-789", result.get("taskId"));
//        assertEquals("FAILED", result.get("status"));
//        assertEquals("PARSING", result.get("stage"));
//        assertEquals("文件格式损坏", result.get("errorMessage"));
//        assertEquals("PARSING", result.get("errorStage"));
//        assertEquals(completeTime, result.get("completeTime"));
//    }
//
//    @Test
//    void getUploadStatus_withNonExistentTask_shouldReturnMcpError() {
//        when(mcpUploadTaskMapper.selectByTaskId("non-existent")).thenReturn(null);
//
//        Map<String, Object> result = knowledgeMcpTools.getUploadStatus("non-existent");
//
//        assertEquals(true, result.get("isError"));
//        assertEquals(40401, result.get("code"));
//        assertEquals("task not found", result.get("message"));
//    }
//
//    // ==================== upload_kb_file ====================
//
//    @Test
//    void uploadKbFile_withBase64Content_shouldReturnSuccessResult() {
//        String base64Content = Base64.getEncoder().encodeToString("测试内容".getBytes());
//        UploadResult uploadResult = UploadResult.builder()
//                .fileName("test.txt").kbId(1L).kbName("测试库").version("v1.0.123")
//                .status("SUCCESS").chunkCount(3).build();
//        when(knowledgeUploadService.uploadFile(any(), any(), any(), any(), any(), any()))
//                .thenReturn(uploadResult);
//
//        Map<String, Object> result = knowledgeMcpTools.uploadKbFile(
//                null, base64Content, "test.txt", "测试库", true);
//
//        assertNotNull(result.get("taskId"));
//        assertEquals("test.txt", result.get("fileName"));
//        assertEquals(1L, result.get("kbId"));
//        assertEquals("测试库", result.get("kbName"));
//        assertEquals("v1.0.123", result.get("version"));
//        assertEquals("SUCCESS", result.get("status"));
//        assertEquals(3, result.get("chunkCount"));
//    }
//
//    @Test
//    void uploadKbFile_withAutoClassifyFalseAndNoKbName_shouldReturnError() {
//        String base64Content = Base64.getEncoder().encodeToString("内容".getBytes());
//
//        Map<String, Object> result = knowledgeMcpTools.uploadKbFile(
//                null, base64Content, "test.txt", null, false);
//
//        assertEquals(true, result.get("isError"));
//        assertEquals(40003, result.get("code"));
//        assertEquals("kb_name is required when auto_classify=false", result.get("message"));
//    }
//
//    @Test
//    void uploadKbFile_withBase64ExceedingLimit_shouldReturnError() {
//        // 构造一个超过 20MB 的 base64 字符串（用 21MB 的字节数组）
//        byte[] largeBytes = new byte[21 * 1024 * 1024];
//        String largeBase64 = Base64.getEncoder().encodeToString(largeBytes);
//
//        Map<String, Object> result = knowledgeMcpTools.uploadKbFile(
//                null, largeBase64, "big.txt", "测试库", true);
//
//        assertEquals(true, result.get("isError"));
//        assertEquals(40001, result.get("code"));
//        assertEquals("base64 file exceeds 20MB limit, please use file_url", result.get("message"));
//    }
//
//    @Test
//    void uploadKbFile_withNeitherUrlNorBase64_shouldReturnError() {
//        Map<String, Object> result = knowledgeMcpTools.uploadKbFile(
//                null, null, "test.txt", "测试库", true);
//
//        assertEquals(true, result.get("isError"));
//        assertEquals(40000, result.get("code"));
//        assertEquals("either file_url or base64_content is required", result.get("message"));
//    }
//
//    @Test
//    void uploadKbFile_withBase64ButNoFileName_shouldReturnError() {
//        String base64Content = Base64.getEncoder().encodeToString("内容".getBytes());
//
//        Map<String, Object> result = knowledgeMcpTools.uploadKbFile(
//                null, base64Content, null, "测试库", true);
//
//        assertEquals(true, result.get("isError"));
//        assertEquals(40000, result.get("code"));
//        assertEquals("file_name is required when using base64_content", result.get("message"));
//    }
//}
