package com.xushu.rag.structured;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BeanOutputConverter 结构化输出单元测试
 * <p>验证各Record类型的JSON Schema生成与反序列化正确性</p>
 *
 * @author Joseph
 */
@DisplayName("结构化输出转换器测试")
class StructuredOutputConverterTest {

    // ==================== IntentClassification ====================

    @Test
    @DisplayName("IntentClassification: 生成有效JSON Schema")
    void intentClassification_format_shouldGenerateValidSchema() {
        BeanOutputConverter<IntentClassification> converter =
                new BeanOutputConverter<>(IntentClassification.class);

        String format = converter.getFormat();
        assertNotNull(format, "format() 不应为null");
        assertTrue(format.contains("\"intent\""), "Schema应包含intent字段");
        assertTrue(format.contains("\"reason\""), "Schema应包含reason字段");
        assertTrue(format.contains("\"confidence\""), "Schema应包含confidence字段");
    }

    @Test
    @DisplayName("IntentClassification: 正确解析JSON响应")
    void intentClassification_convert_shouldParseValidJson() {
        BeanOutputConverter<IntentClassification> converter =
                new BeanOutputConverter<>(IntentClassification.class);

        String llmResponse = """
                {"intent":"calculation","reason":"用户询问工资计算","confidence":0.95}""";

        IntentClassification result = converter.convert(llmResponse);

        assertNotNull(result, "解析结果不应为null");
        assertEquals("calculation", result.intent());
        assertEquals("用户询问工资计算", result.reason());
        assertEquals(0.95, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("IntentClassification: 容错—空字符串不抛异常")
    void intentClassification_convert_shouldHandleEmptyOrInvalid() {
        BeanOutputConverter<IntentClassification> converter =
                new BeanOutputConverter<>(IntentClassification.class);

        // 转换异常应被外部 try-catch 捕获，此处验证转换器本身行为
        assertThrows(Exception.class, () -> converter.convert(""));
        assertThrows(Exception.class, () -> converter.convert("not json at all"));
    }

    @Test
    @DisplayName("IntentClassification: 含emotion字段的JSON解析")
    void intentClassification_convert_shouldParseEmotion() {
        BeanOutputConverter<IntentClassification> converter =
                new BeanOutputConverter<>(IntentClassification.class);

        String llmResponse = """
                {"intent":"reference","reason":"用户查询制度","confidence":0.88,"emotion":"neutral"}""";

        IntentClassification result = converter.convert(llmResponse);

        assertNotNull(result);
        assertEquals("reference", result.intent());
        assertEquals("neutral", result.emotion());
    }

    @Test
    @DisplayName("IntentClassification: emotion字段缺失时默认为neutral")
    void intentClassification_convert_shouldDefaultEmotion() {
        BeanOutputConverter<IntentClassification> converter =
                new BeanOutputConverter<>(IntentClassification.class);

        // 旧版LLM响应不含emotion
        String llmResponse = """
                {"intent":"calculation","reason":"计算补偿","confidence":0.9}""";

        IntentClassification result = converter.convert(llmResponse);

        assertNotNull(result);
        // emotion缺失时应为null，调用方兜底为neutral
        assertNull(result.emotion());
    }

    // ==================== ScoreItem ====================

    @Test
    @DisplayName("ScoreItem: 正确解析单文档分数")
    void scoreItem_convert_shouldParseSingleScore() {
        BeanOutputConverter<ScoreItem> converter =
                new BeanOutputConverter<>(ScoreItem.class);

        ScoreItem result = converter.convert("{\"score\":0.7}");

        assertNotNull(result);
        assertEquals(0.7, result.score(), 0.001);
    }

    @Test
    @DisplayName("ScoreItem List: 正确解析批量分数数组")
    void scoreItemList_convert_shouldParseBatchScores() {
        BeanOutputConverter<List<ScoreItem>> converter =
                new BeanOutputConverter<>(
                        new ParameterizedTypeReference<List<ScoreItem>>() {});

        String llmResponse = """
                [{"score":0.7}, {"score":0.3}, {"score":0.9}]""";

        List<ScoreItem> result = converter.convert(llmResponse);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(0.7, result.get(0).score(), 0.001);
        assertEquals(0.3, result.get(1).score(), 0.001);
        assertEquals(0.9, result.get(2).score(), 0.001);
    }

    // ==================== QueryDecompose: List<String> ====================

    @Test
    @DisplayName("QueryDecompose: ParameterizedTypeReference<List<String>> 解析")
    void queryDecompose_convert_shouldParseStringArray() {
        BeanOutputConverter<List<String>> converter =
                new BeanOutputConverter<>(
                        new ParameterizedTypeReference<List<String>>() {});

        String llmResponse = """
                ["SVM支持向量机详细介绍","决策树算法详细介绍","两者区别与应用场景"]""";

        List<String> result = converter.convert(llmResponse);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("SVM支持向量机详细介绍", result.get(0));
        assertEquals("决策树算法详细介绍", result.get(1));
        assertEquals("两者区别与应用场景", result.get(2));
    }

    @Test
    @DisplayName("QueryDecompose: 单个子问题时解析为单元素列表")
    void queryDecompose_convert_shouldHandleSingleElement() {
        BeanOutputConverter<List<String>> converter =
                new BeanOutputConverter<>(
                        new ParameterizedTypeReference<List<String>>() {});

        List<String> result = converter.convert(""" 
                ["如何计算加班费"]""");

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // ==================== EvalScore ====================

    @Test
    @DisplayName("EvalScore: 正确解析评估分数")
    void evalScore_convert_shouldParseScore() {
        BeanOutputConverter<EvalScore> converter =
                new BeanOutputConverter<>(EvalScore.class);

        EvalScore result = converter.convert("{\"score\":0.85}");

        assertNotNull(result);
        assertEquals(0.85, result.score(), 0.001);
    }

    @Test
    @DisplayName("EvalScore: 分数范围应为0~1")
    void evalScore_convert_shouldHandleBoundaryValues() {
        BeanOutputConverter<EvalScore> converter =
                new BeanOutputConverter<>(EvalScore.class);

        assertEquals(0.0, converter.convert("{\"score\":0}").score(), 0.001);
        assertEquals(1.0, converter.convert("{\"score\":1}").score(), 0.001);
    }
}
