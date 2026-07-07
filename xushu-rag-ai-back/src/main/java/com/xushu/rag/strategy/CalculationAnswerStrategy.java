package com.xushu.rag.strategy;

import com.xushu.rag.structured.RetrieveAnswerRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计算类意图策略 — 严格数值约束 + CoT推理 + 事后数值校验。
 * <p>从 {@link RetrievalAnswerStrategy} 中独立出来，新增：
 * <ul>
 *   <li>指标精确匹配约束（"营业收入"≠"主营业务收入"）</li>
 *   <li>单位强制校验与换算要求</li>
 *   <li>禁止跨文档推导（不做隐含除法/反推）</li>
 *   <li>事后数值幻觉校验（数字是否来源于上下文）</li>
 * </ul>
 * </p>
 * <p>覆盖：calculation</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class CalculationAnswerStrategy implements AnswerGenerationStrategy {

    private final BeanOutputConverter<RetrieveAnswerRecord> converter;

    /** 匹配数值+可选单位的模式（用于事后校验） */
    private static final Pattern NUMERIC_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|亿|千|百)?\\s*(元|美元|人民币|%|人|个|次|条|天|年|月)?");

    public CalculationAnswerStrategy() {
        this.converter = new BeanOutputConverter<>(RetrieveAnswerRecord.class);
    }

    @Override
    public List<String> supportedCategories() {
        return List.of("calculation");
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        String schemaText = converter.getFormat();

        return (systemPrompt + """


                【回答要求 - 数值计算推理】
                你必须基于上下文进行详细推理，按以下JSON格式返回答案（不要输出其他文字）：

                {schema}

                字段说明：
                - stepByStepAnalysis: 逐步推理过程。每步说明你从上下文中查到了什么数值、
                  如何核对指标定义是否匹配。如果指标不在上下文中，第1步就明确说明。
                - reasoningSummary: 一句话总结推理结论（约50字）
                - relevantSources: 你实际引用的来源列表，从上下文中"📚 可引用的文档来源"
                  的条目中选取，格式必须完全一致。未引用任何来源时传空数组[]。
                - finalAnswer: 面向用户的最终答案。

                【数值专项约束（最高优先级）】
                1. 指标精确匹配：问题中的指标名称必须与上下文中完全一致才能使用。
                   例如："营业收入"≠"主营业务收入"，"净利润"≠"归母净利润"。
                   若上下文中仅有近似指标，明确告知用户差异，不要混用。
                2. 单位强制标注：答案必须包含单位（万元/元/美元/人民币/%等）。
                   若上下文用"万元"而问题期望"元"，需进行换算并在答案中注明换算过程。
                3. 禁止推导：不允许跨文档计算（如A文档的人数和B文档的营收不能组合推导"人均"）。
                   不允许反推趋势估算。只能使用上下文中已有的现成数值。
                4. 缺失处理：如果上下文中没有目标指标的明确数值，回答"知识库中暂无该数据"。
                   不要列出相关但不相同的数据来凑答案。
                """).replace("{schema}", schemaText);
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
            log.info("[Calculation] 结构化解析成功, CoT={}字, sources={}, answer={}字",
                    record.stepByStepAnalysis() != null ? record.stepByStepAnalysis().length() : 0,
                    record.relevantSources(),
                    answer != null ? answer.length() : 0);
            return answer != null ? answer : "抱歉，暂时无法回答您的问题。";
        } catch (Exception e) {
            log.warn("[Calculation] BeanOutputConverter解析失败，尝试fallback提取finalAnswer: {}",
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
            return fallbackExtractField(rawOutput, "stepByStepAnalysis");
        }
    }

    @Override
    public ValidationResult validate(String rawOutput, String context) {
        // 1. 引用校验（复用 RetrievalAnswerStrategy 的逻辑）
        try {
            RetrieveAnswerRecord record = converter.convert(rawOutput);
            List<String> claimed = record.relevantSources();
            if (claimed != null && !claimed.isEmpty()) {
                Set<String> validSources = extractValidSourcesFromContext(context);
                if (!validSources.isEmpty()) {
                    List<String> fakeSources = new ArrayList<>();
                    for (String source : claimed) {
                        if (!validSources.contains(source.trim())) {
                            fakeSources.add(source.trim());
                        }
                    }
                    if (!fakeSources.isEmpty()) {
                        String msg = "检出幻觉引用：" + String.join(", ", fakeSources);
                        log.warn("[Calculation] 引用校验告警: {}", msg);
                        return ValidationResult.warn(msg);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Calculation] 引用校验跳过（解析失败）: {}", e.getMessage());
        }

        // 2. 数值校验：提取 answer 中的所有数值，检查是否出现在上下文中
        try {
            RetrieveAnswerRecord record = converter.convert(rawOutput);
            String finalAnswer = record.finalAnswer();
            if (finalAnswer != null && !finalAnswer.isEmpty()
                    && context != null && !context.isEmpty()) {
                Matcher m = NUMERIC_PATTERN.matcher(finalAnswer);
                List<String> suspectNumbers = new ArrayList<>();
                while (m.find()) {
                    String matched = m.group().trim();
                    // 跳过纯年份（如"2024"）
                    if (matched.matches("\\d{4}") && m.group(2) == null && m.group(3) == null) {
                        continue;
                    }
                    if (!context.contains(matched)) {
                        suspectNumbers.add(matched);
                    }
                }
                if (!suspectNumbers.isEmpty()) {
                    String msg = "答案中包含未被上下文印证的数值："
                            + String.join(", ", suspectNumbers)
                            + "，可能为幻觉或推导值。";
                    log.warn("[Calculation] 数值校验告警: {}", msg);
                    return ValidationResult.warn(msg);
                }
            }
        } catch (Exception e) {
            log.warn("[Calculation] 数值校验跳过（解析失败）: {}", e.getMessage());
        }

        return ValidationResult.allGood();
    }

    // ========== 私有辅助 ==========

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

    private String fallbackExtractFinalAnswer(String rawOutput) {
        String extracted = fallbackExtractField(rawOutput, "finalAnswer");
        if (extracted != null && !extracted.isEmpty()) {
            return extracted;
        }
        log.warn("[Calculation] 无法提取finalAnswer，返回原始输出作为fallback");
        return rawOutput;
    }

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
