package com.xushu.rag.strategy;

import com.xushu.rag.structured.RetrieveAnswerRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索类意图策略（默认策略）。
 * <p>强 CoT + 严格来源引用 + 事后引用校验。</p>
 * <p>覆盖：reference / calculation / aggregation / comparison，以及未匹配的任何意图。</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class RetrievalAnswerStrategy implements AnswerGenerationStrategy {

    private final BeanOutputConverter<RetrieveAnswerRecord> converter;

    public RetrievalAnswerStrategy() {
        this.converter = new BeanOutputConverter<>(RetrieveAnswerRecord.class);
    }

    @Override
    public List<String> supportedCategories() {
        return List.of("reference", "aggregation", "comparison");
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        String schemaText = converter.getFormat();

        return systemPrompt + """


                【回答要求 - 检索增强推理】
                你必须基于上下文进行详细推理，按以下JSON格式返回答案（不要输出其他文字）：

                %s

                字段说明：
                - stepByStepAnalysis: 逐步推理过程，至少5步。每步说明你从上下文中查到了什么、
                  如何判断、如何计算/对比。如果答案不在上下文中，在第1步就明确说明。
                - reasoningSummary: 一句话总结推理结论（约50字）
                - relevantSources: 你实际引用的来源列表，从上下文中"📚 可引用的文档来源"
                  的条目中选取，格式必须完全一致。未引用任何来源时传空数组[]。
                - finalAnswer: 面向用户的最终答案。如果上下文没有相关信息，
                  回答"知识库中暂无相关内容"。
                """.formatted(schemaText);
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出格式】你的回答必须是纯JSON对象，字段顺序：stepByStepAnalysis, "
                + "reasoningSummary, relevantSources, finalAnswer。"
                + "不要用```json```包裹，不要添加任何解释文字。直接输出JSON。";
    }

    @Override
    public String extractAnswer(String rawOutput) {
        try {
            RetrieveAnswerRecord record = converter.convert(rawOutput);
            String answer = record.finalAnswer();
            log.info("[Retrieval] 结构化解析成功, CoT={}字, sources={}, answer={}字",
                    record.stepByStepAnalysis() != null ? record.stepByStepAnalysis().length() : 0,
                    record.relevantSources(),
                    answer != null ? answer.length() : 0);
            return answer != null ? answer : "抱歉，暂时无法回答您的问题。";
        } catch (Exception e) {
            log.warn("[Retrieval] BeanOutputConverter解析失败，尝试fallback提取finalAnswer: {}",
                    e.getMessage());
            return fallbackExtractFinalAnswer(rawOutput);
        }
    }

    @Override
    public String extractThinking(String rawOutput) {
        try {
            RetrieveAnswerRecord record = converter.convert(rawOutput);
            if (record.stepByStepAnalysis() == null
                    || record.stepByStepAnalysis().trim().isEmpty()) {
                return null;
            }
            return record.stepByStepAnalysis();
        } catch (Exception e) {
            // 解析失败时尝试用 ObjectMapper 手动提取
            return fallbackExtractField(rawOutput, "stepByStepAnalysis");
        }
    }

    @Override
    public ValidationResult validate(String rawOutput, String context) {
        try {
            RetrieveAnswerRecord record = converter.convert(rawOutput);
            List<String> claimed = record.relevantSources();
            if (claimed == null || claimed.isEmpty()) {
                return ValidationResult.allGood();
            }

            Set<String> validSources = extractValidSourcesFromContext(context);
            if (validSources.isEmpty()) {
                return ValidationResult.allGood();  // 上下文无来源列表，无法校验
            }

            List<String> fakeSources = new ArrayList<>();
            for (String source : claimed) {
                String trimmed = source.trim();
                if (!validSources.contains(trimmed)) {
                    fakeSources.add(trimmed);
                }
            }

            if (!fakeSources.isEmpty()) {
                String msg = "检出幻觉引用：" + String.join(", ", fakeSources)
                        + "，这些来源未出现在检索结果中。有效来源：" + validSources;
                log.warn("[Retrieval] 引用校验告警: {}", msg);
                return ValidationResult.warn(msg);
            }
            return ValidationResult.allGood();

        } catch (Exception e) {
            log.warn("[Retrieval] 引用校验跳过（解析失败）: {}", e.getMessage());
            return ValidationResult.allGood();
        }
    }

    // ========== 私有辅助 ==========

    /** 从 ContextBuildNode 输出的上下文中提取 "📚 可引用的文档来源" 列表 */
    private Set<String> extractValidSourcesFromContext(String context) {
        Set<String> sources = new LinkedHashSet<>();
        if (context == null || context.isEmpty()) {
            return sources;
        }
        int markerIdx = context.indexOf("📚 可引用的文档来源：");
        if (markerIdx == -1) {
            return sources;
        }
        String section = context.substring(markerIdx);
        for (String line : section.split("\n")) {
            line = line.trim();
            if (line.startsWith("- ")) {
                sources.add(line.substring(2).trim());
            }
        }
        return sources;
    }

    /** BeanOutputConverter 失败时从 JSON 子串提取 finalAnswer */
    private String fallbackExtractFinalAnswer(String rawOutput) {
        String extracted = fallbackExtractField(rawOutput, "finalAnswer");
        if (extracted != null && !extracted.isEmpty()) {
            return extracted;
        }
        log.warn("[Retrieval] 无法提取finalAnswer，返回原始输出作为fallback");
        return rawOutput;
    }

    /** 从 JSON 子串中按字段名提取字符串值（最简正则实现） */
    private String fallbackExtractField(String rawOutput, String fieldName) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return null;
        }
        Pattern p = Pattern.compile(
                "\"" + fieldName + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(rawOutput);
        if (m.find()) {
            return m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
        }
        return null;
    }
}
