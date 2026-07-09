package com.xushu.rag.service;

import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.pojo.dto.UploadResult;
import com.xushu.rag.utils.AliOssUtil;
import com.xushu.rag.utils.JavaDocumentParser;
import com.xushu.rag.utils.ImageDescriber;
import com.xushu.rag.utils.ImageExtractor;
import com.xushu.rag.utils.PythonScriptExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KnowledgeUploadService 单元测试
 * <p>验证从 KnowledgeController.uploadWithKb 抽取的上传逻辑行为正确。</p>
 *
 * @author Joseph
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeUploadServiceTest {

    @Mock
    private AliOssUtil aliOssUtil;
    @Mock
    private TokenTextSplitter tokenTextSplitter;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private DocumentService documentService;
    @Mock
    private DocumentPageService documentPageService;
    @Mock
    private MilvusV2InsertService milvusV2InsertService;
    @Mock
    private PythonScriptExecutor pythonScriptExecutor;
    @Mock
    private JavaDocumentParser javaDocumentParser;
    @Mock
    private ImageExtractor imageExtractor;
    @Mock
    private ImageDescriber imageDescriber;

    @InjectMocks
    private KnowledgeUploadService knowledgeUploadService;

    // ========== P0: uploadFile 成功上传到指定 kbId ==========

    @Test
    void uploadFile_withSpecifiedKbId_shouldReturnSuccessResult() throws Exception {
        // Given
        String fileContent = "hello world";
        MultipartFile mockFile = createMockTxtFile("test.txt", fileContent);

        when(aliOssUtil.upload(any(byte[].class), anyString())).thenReturn("https://oss.example.com/test.txt");
        when(pythonScriptExecutor.executeParser(anyString())).thenReturn(createParseResultWithChunks(fileContent));
        when(tokenTextSplitter.apply(anyList())).thenReturn(Collections.singletonList(
                new org.springframework.ai.document.Document(fileContent)));
        when(documentService.generateVersion(eq(1L), eq("test.txt"))).thenReturn("v1.0.1234567890");

        // When
        UploadResult result = knowledgeUploadService.uploadFile(
                mockFile, 1L, "测试知识库", false, 300, 50);

        // Then — 验证返回的行为结果，不验证内部调用次数
        assertNotNull(result);
        assertEquals("test.txt", result.getFileName());
        assertEquals(1L, result.getKbId());
        assertEquals("测试知识库", result.getKbName());
        assertEquals("success", result.getStatus());
        assertNotNull(result.getVersion());
        assertTrue(result.getChunkCount() > 0);
    }

    // ========== P0: autoClassify=true 且 kbId=null 使用推荐知识库 ==========

    @Test
    void uploadFile_withAutoClassifyAndNoKbId_shouldUseSuggestedKnowledgeBase() throws Exception {
        // Given
        String fileContent = "人工智能相关内容";
        MultipartFile mockFile = createMockTxtFile("ai.txt", fileContent);

        when(aliOssUtil.upload(any(byte[].class), anyString())).thenReturn("https://oss.example.com/ai.txt");
        when(pythonScriptExecutor.executeParser(anyString())).thenReturn(createParseResultWithChunks(fileContent));
        when(tokenTextSplitter.apply(anyList())).thenReturn(Collections.singletonList(
                new org.springframework.ai.document.Document(fileContent)));

        // suggestKnowledgeBase 返回已有知识库（is_new=false）
        Map<String, Object> suggestData = new HashMap<>();
        suggestData.put("suggested_kb_name", "推荐知识库");
        suggestData.put("is_new", false);
        suggestData.put("kb_id", 5L);
        when(knowledgeBaseService.suggestKnowledgeBase(anyString()))
                .thenReturn(new BaseResponse<>(0, suggestData));
        when(documentService.generateVersion(eq(5L), eq("ai.txt"))).thenReturn("v1.0.999");

        // When
        UploadResult result = knowledgeUploadService.uploadFile(
                mockFile, null, null, true, 300, 50);

        // Then — 验证使用了推荐的知识库
        assertNotNull(result);
        assertEquals(5L, result.getKbId());
        assertEquals("推荐知识库", result.getKbName());
        assertEquals("success", result.getStatus());
    }

    // ========== P1: 文件处理失败返回 failed 状态 ==========

    @Test
    void uploadFile_whenOssUploadFails_shouldReturnFailedResult() throws Exception {
        // Given — 只 stub 失败前会用到的方法
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("fail.txt");
        when(mockFile.getBytes()).thenReturn("hello world".getBytes());

        when(aliOssUtil.upload(any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("OSS存储不可用"));

        // When
        UploadResult result = knowledgeUploadService.uploadFile(
                mockFile, 1L, "测试知识库", false, 300, 50);

        // Then — 验证失败时返回 failed 状态和错误信息
        assertNotNull(result);
        assertEquals("fail.txt", result.getFileName());
        assertEquals("failed", result.getStatus());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("OSS存储不可用"));
    }

    // ========== P1: uploadFiles 遍历多文件 ==========

    @Test
    void uploadFiles_shouldProcessEachFileAndReturnResults() throws Exception {
        // Given
        MultipartFile file1 = createMockTxtFile("file1.txt", "content1");
        MultipartFile file2 = createMockTxtFile("file2.txt", "content2");

        when(aliOssUtil.upload(any(byte[].class), anyString())).thenReturn("https://oss.example.com/file");
        when(pythonScriptExecutor.executeParser(anyString())).thenReturn(createParseResultWithChunks("content"));
        when(tokenTextSplitter.apply(anyList())).thenReturn(Collections.singletonList(
                new org.springframework.ai.document.Document("content")));
        when(documentService.generateVersion(eq(1L), anyString())).thenReturn("v1.0.1");

        // When
        List<UploadResult> results = knowledgeUploadService.uploadFiles(
                Arrays.asList(file1, file2), 1L, "测试知识库", false, 300, 50);

        // Then — 验证每个文件都有对应结果
        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("file1.txt", results.get(0).getFileName());
        assertEquals("file2.txt", results.get(1).getFileName());
        assertEquals("success", results.get(0).getStatus());
        assertEquals("success", results.get(1).getStatus());
    }

    // ========== Helper Methods ==========

    private MultipartFile createMockTxtFile(String filename, String content) throws Exception {
        MultipartFile mockFile = mock(MultipartFile.class);
        byte[] contentBytes = content.getBytes();
        when(mockFile.getOriginalFilename()).thenReturn(filename);
        when(mockFile.getBytes()).thenReturn(contentBytes);
        when(mockFile.getInputStream()).thenReturn(new ByteArrayInputStream(contentBytes));
        return mockFile;
    }

    private JSONObject createParseResultWithChunks(String text) {
        JSONObject result = new JSONObject();
        result.put("text", text);
        JSONObject chunk = new JSONObject();
        chunk.put("page", 1);
        chunk.put("type", "TEXT");
        chunk.put("text", text);
        result.put("chunks", new com.alibaba.fastjson.JSONArray().fluentAdd(chunk));
        return result;
    }
}
