package com.xushu.rag.strategy;

import com.xushu.rag.structured.PlanningAnswerRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规划类意图策略。
 * <p>轻 CoT（步骤列表，不强求出处）+ 具体数据防幻觉（距离/价格/时间等数字+单位组合）。</p>
 * <p>覆盖：planning</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class PlanningAnswerStrategy implements AnswerGenerationStrategy {

    private final BeanOutputConverter<PlanningAnswerRecord> converter;

    /** 匹配具体数字+单位的模式（用于防幻觉校验） */
    private static final Pattern CONCRETE_DATA_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(米|公里|km|千米|小时|分钟|元|块|天|度|℃|人|个|次|条)");

    public PlanningAnswerStrategy() {
        this.converter = new BeanOutputConverter<>(PlanningAnswerRecord.class);
    }

    @Override
    public List<String> supportedCategories() {
        return List.of("planning");
    }

    @Override
    public String enrichSystemPrompt(String systemPrompt) {
        String schemaText = converter.getFormat();

        return systemPrompt + """


                【规划推理要求】
                请基于材料进行规划推理，按以下JSON格式返回：

                %s

                字段说明：
                - planSteps: 规划步骤列表，每步一句话描述做什么
                - considerations: 注意事项，包括约束条件、需要用户确认的信息、
                  材料不足之处、哪些部分需要调用外部工具
                - finalAnswer: 面向用户的最终方案
                禁止捏造具体数据（距离、价格、时间等），除非上下文中有明确数值。
                """.formatted(schemaText);
    }

    @Override
    public String enrichUserTail(String tail) {
        return tail + "\n\n"
                + "【输出格式】返回纯JSON对象，字段顺序：planSteps, considerations, finalAnswer。"
                + "不要用```json```包裹。";
    }

    @Override
    public String extractAnswer(String rawOutput) {
        try {
            PlanningAnswerRecord record = converter.convert(rawOutput);
            String answer = record.finalAnswer();
            log.info("[Planning] 结构化解析成功, steps={}字, considerations={}字",
                    record.planSteps() != null ? record.planSteps().length() : 0,
                    record.considerations() != null ? record.considerations().length() : 0);
            return answer != null ? answer : "抱歉，暂时无法为您规划。";
        } catch (Exception e) {
            log.warn("[Planning] BeanOutputConverter解析失败: {}", e.getMessage());
            return fallbackExtractField(rawOutput, "finalAnswer", rawOutput);
        }
    }

    @Override
    public String extractThinking(String rawOutput) {
        try {
            PlanningAnswerRecord record = converter.convert(rawOutput);
            StringBuilder sb = new StringBuilder();
            if (record.planSteps() != null && !record.planSteps().trim().isEmpty()) {
                sb.append("📋 规划步骤：\n").append(record.planSteps());
            }
            if (record.considerations() != null && !record.considerations().trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("⚠️ 注意事项：\n").append(record.considerations());
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ValidationResult validate(String rawOutput, String context) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return ValidationResult.allGood();
        }
        if (context == null || context.isEmpty()) {
            return ValidationResult.allGood();
        }

        // 规划模式：只校验是否捏造了具体数据
        Matcher m = CONCRETE_DATA_PATTERN.matcher(rawOutput);
        List<String> suspectData = new ArrayList<>();
        while (m.find()) {
            String matched = m.group().trim();
            if (!context.contains(m.group(0))) {
                suspectData.add(matched);
            }
        }

        if (!suspectData.isEmpty()) {
            String msg = "规划中包含未被上下文印证的具体数据："
                    + String.join(", ", suspectData)
                    + "。请人工确认。（不影响结果输出）";
            log.warn("[Planning] 具体数据校验告警: {}", msg);
            return ValidationResult.warn(msg);
        }
        return ValidationResult.allGood();
    }

    // ========== 私有辅助 ==========

    private String fallbackExtractField(String rawOutput, String fieldName, String fallback) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return fallback;
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
        return fallback;
    }
}
