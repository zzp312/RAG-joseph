package com.xushu.rag.classifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * L1 关键词快速意图分类（0ms延迟, 0 token消耗）
 * <p>覆盖高频问候语和指示词，平票放弃转入L2</p>
 *
 * @author Joseph
 */
@Slf4j
@Component
public class KeywordIntentClassifier implements IntentClassifier {

    /** 关键词 → 意图类别映射（多个类别则平票放弃） */
    private static final Map<String, List<Category>> KEYWORD_MAP = new LinkedHashMap<>();

    static {
        // 闲聊类
        for (String kw : new String[]{"你好", "谢谢", "ok", "好的", "嗯", "哈哈", "不错", "再见", "早", "晚上好"}) {
            KEYWORD_MAP.put(kw, Collections.singletonList(Category.CHITCHAT));
        }
        // 计算类
        for (String kw : new String[]{"帮我算", "计算", "多少钱", "多少天", "加班费", "工资", "薪资", "补偿",
                "社保基数", "公积金", "税前", "税后", "年假折算", "公式"}) {
            KEYWORD_MAP.put(kw, Collections.singletonList(Category.CALCULATION));
        }
        // 操作类
        for (String kw : new String[]{"我要办理", "帮我录入", "提交", "申请", "入职", "离职", "请假",
                "审批", "合同", "签署", "盖章", "退工", "转正"}) {
            KEYWORD_MAP.put(kw, Collections.singletonList(Category.OPERATION));
        }
        // 资料查阅类
        for (String kw : new String[]{"帮助", "怎么用", "使用说明", "什么是", "解释", "定义", "政策",
                "规定", "法规", "制度", "流程", "指南", "文档"}) {
            KEYWORD_MAP.put(kw, Collections.singletonList(Category.REFERENCE));
        }
        // 转人工
        for (String kw : new String[]{"转人工", "人工客服", "找人工", "叫人来", "找客服", "人工服务"}) {
            KEYWORD_MAP.put(kw, Collections.singletonList(Category.ESCALATION));
        }
    }

    @Override
    public ClassifyResult classify(String question) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        String q = question.trim();
        Map<Category, Integer> votes = new EnumMap<>(Category.class);

        for (Map.Entry<String, List<Category>> entry : KEYWORD_MAP.entrySet()) {
            if (q.contains(entry.getKey())) {
                for (Category cat : entry.getValue()) {
                    votes.merge(cat, 1, Integer::sum);
                }
            }
        }

        if (votes.isEmpty()) {
            return null; // 未命中 → 转入L2
        }

        // 找出得票最高的类别
        Category best = null;
        int maxVotes = 0;
        boolean tie = false;
        for (Map.Entry<Category, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > maxVotes) {
                best = entry.getKey();
                maxVotes = entry.getValue();
                tie = false;
            } else if (entry.getValue() == maxVotes) {
                tie = true; // 平票
            }
        }

        if (tie || best == null) {
            log.debug("[L1关键词] 平票或未命中, votes={}, 转入L2", votes);
            return null; // 平票放弃 → 转入L2
        }

        log.info("[L1关键词] 命中 category={}, votes={}", best, votes);
        return new ClassifyResult(best, "L1", 0);
    }
}
