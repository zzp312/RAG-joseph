package com.xushu.rag.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xushu.rag.service.HybridSearchService;
import com.xushu.rag.structured.EvalScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RAG 质量评估控制器（LLM-as-Judge 模式）
 * <p>接收测试用例 JSON，用 LLM 评估 Faithfulness / AnswerRelevancy / ContextPrecision / ContextRecall</p>
 * <p>使用BeanOutputConverter实现结构化评分输出，fallback保留原有正则数字提取</p>
 *
 * @author Joseph
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
public class EvaluationController {

    private final ChatClient chatClient;
    private final HybridSearchService hybridSearchService;
    private final BeanOutputConverter<EvalScore> converter;

    public EvaluationController(ChatModel chatModel,
                             HybridSearchService hybridSearchService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.hybridSearchService = hybridSearchService;
        this.converter = new BeanOutputConverter<>(EvalScore.class);
    }

    /**
     * 采集真实检索结果：只传 question，自动走 Milvus 检索 + LLM 生成，返回 eval 格式
     * <pre>
     * POST /api/v1/admin/eval/collect
     * Body: {"question": "啥是SVM"}
     * Response: {"question":"啥是SVM","answer":"...","contexts":["...","..."],"groundTruth":""}
     * </pre>
     */
    @PostMapping(value = "/eval/collect", produces = MediaType.APPLICATION_JSON_VALUE)
    public String collect(@RequestBody JSONObject body) {
        String question = body.getString("question");

        // ① 混合检索（BM25 不可用时自动降级为纯向量检索）
        List<Document> docs = hybridSearchService.search(question, 10, null);
        List<String> contexts = docs.stream().map(Document::getText).collect(Collectors.toList());
        log.info("[Eval-Collect] question={}, 检索到{}个chunk", question, docs.size());

        // ② LLM 生成（对标工作流上下文注入格式）
        String answer;
        try {
            String ctx = String.join("\n---\n", contexts);
            String prompt = question + "\n\n" +
                    "Context information is below, surrounded by ---------------------\n" +
                    "---------------------\n" + ctx + "\n---------------------\n\n" +
                    "Given the context and provided history information and not prior knowledge, " +
                    "reply to the user comment.";
            answer = chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            answer = "生成失败: " + e.getMessage();
        }

        JSONObject result = new JSONObject();
        result.put("question", question);
        result.put("answer", answer != null ? answer : "");
        result.put("contexts", contexts);
        result.put("groundTruth", "");
        result.put("_copyHint", "复制此JSON到 eval-samples.json 数组中，填写 groundTruth 后跑 /eval");
        return result.toJSONString();
    }

    /**
     * 一键评估：传问题 → 检索 + 生成 + 打分，直接看质量
     * <pre>
     * POST /api/v1/admin/eval/run
     * Body: {"question": "啥是SVM"}
     * Response: {"answer":"SVM是...","faithfulness":0.9,"answerRelevancy":0.85,"contextPrecision":0.8,"contextRecall":-1}
     * </pre>
     */
    @PostMapping(value = "/eval/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public String runEval(@RequestBody JSONObject body) {
        String question = body.getString("question");

        // ① 混合检索（BM25 不可用时自动降级为纯向量检索）
        List<Document> docs = hybridSearchService.search(question, 10, null);
        List<String> contexts = docs.stream().map(Document::getText).collect(Collectors.toList());

        // ② 生成
        String answer;
        try {
            String ctx = String.join("\n---\n", contexts);
            String prompt = question + "\n\n" +
                    "Context information is below, surrounded by ---------------------\n" +
                    "---------------------\n" + ctx + "\n---------------------\n\n" +
                    "Given the context and provided history information and not prior knowledge, " +
                    "reply to the user comment.";
            answer = chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            answer = "生成失败";
        }

        // ③ 打分
        double faith = evalFaithfulness(answer, contexts);
        double relevancy = evalAnswerRelevancy(question, answer);
        double precision = evalContextPrecision(question, contexts);

        JSONObject result = new JSONObject();
        result.put("question", question);
        result.put("answer", answer);
        result.put("contextCount", contexts.size());
        result.put("faithfulness", round(faith));
        result.put("answerRelevancy", round(relevancy));
        result.put("contextPrecision", round(precision));
        return result.toJSONString();
    }

    /**
     * RAG 评估端点
     * <pre>
     * POST /api/v1/admin/eval
     * Body: [{ "question": "...", "answer": "...", "contexts": ["...", "..."], "groundTruth": "..." }]
     * </pre>
     */
    @PostMapping(value = "/eval", produces = MediaType.APPLICATION_JSON_VALUE)
    public String evaluate(@RequestBody String body) {
        JSONArray cases;
        try {
            cases = JSON.parseArray(body);
        } catch (Exception e) {
            return "{\"error\":\"Invalid JSON array\"}";
        }
        if (cases == null || cases.isEmpty()) {
            return "{\"error\":\"At least one test case required\"}";
        }

        double totalFaith = 0, totalRel = 0, totalPrecision = 0, totalRecall = 0;
        AtomicInteger count = new AtomicInteger(0);

        for (int i = 0; i < cases.size(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String answer = c.getString("answer");
            JSONArray ctxArr = c.getJSONArray("contexts");
            List<String> contexts = ctxArr == null ? List.of()
                    : ctxArr.toJavaList(String.class);
            String groundTruth = c.getString("groundTruth");

            totalFaith += evalFaithfulness(answer, contexts);
            totalRel += evalAnswerRelevancy(c.getString("question"), answer);
            totalPrecision += evalContextPrecision(c.getString("question"), contexts);
            totalRecall += evalContextRecall(groundTruth, contexts);
            count.incrementAndGet();
        }

        int n = count.get();
        JSONObject result = new JSONObject();
        result.put("testCases", n);
        result.put("faithfulness", round(totalFaith / n));
        result.put("answerRelevancy", round(totalRel / n));
        result.put("contextPrecision", round(totalPrecision / n));
        result.put("contextRecall", round(totalRecall / n));
        return result.toJSONString();
    }

    /** 评估答案是否仅基于上下文（不编造） */
    private double evalFaithfulness(String answer, List<String> contexts) {
        String ctx = String.join("\n---\n", contexts);
        String prompt = """
            评估以下回答是否严格基于提供的上下文。给出0-1之间的分数：
            0 = 完全编造，上下文不包含相关信息
            1 = 完全基于上下文，无任何外部信息
            只返回数字，不要解释。

            上下文：
            %s

            回答：
            %s
            """.formatted(ctx.substring(0, Math.min(ctx.length(), 3000)), answer.substring(0, Math.min(answer.length(), 1000)));
        return scoreCall(prompt);
    }

    /** 评估答案与问题的相关性 */
    private double evalAnswerRelevancy(String question, String answer) {
        String prompt = """
            评估以下回答与问题的相关性。给出0-1之间的分数：
            0 = 完全不相关
            1 = 完美回答，精准回应问题
            只返回数字，不要解释。

            问题：%s
            回答：%s
            """.formatted(question.substring(0, Math.min(question.length(), 500)),
                    answer.substring(0, Math.min(answer.length(), 1000)));
        return scoreCall(prompt);
    }

    /** 评估上下文中有多少是相关的 */
    private double evalContextPrecision(String question, List<String> contexts) {
        double total = 0;
        for (String ctx : contexts) {
            String prompt = """
                判断以下文本片段是否有助于回答这个问题。0=无关, 1=相关。
                只返回数字。

                问题：%s
                文本：%s
                """.formatted(question.substring(0, Math.min(question.length(), 200)),
                        ctx.substring(0, Math.min(ctx.length(), 500)));
            total += scoreCall(prompt);
        }
        return contexts.isEmpty() ? 0 : total / contexts.size();
    }

    /** 评估参考答案在上下文中的覆盖度 */
    private double evalContextRecall(String groundTruth, List<String> contexts) {
        if (groundTruth == null || groundTruth.isEmpty()) return -1;
        String ctx = String.join(" ", contexts);
        String prompt = """
            判断以下参考信息是否被文本片段覆盖。0=未覆盖, 1=完整覆盖。
            只返回数字。

            参考信息：%s
            文本片段：%s
            """.formatted(groundTruth.substring(0, Math.min(groundTruth.length(), 500)),
                    ctx.substring(0, Math.min(ctx.length(), 3000)));
        return scoreCall(prompt);
    }

    private double scoreCall(String prompt) {
        try {
            // 注入JSON Schema格式指令
            String format = converter.getFormat();
            String fullPrompt = prompt + "\n" + format;

            String resp = chatClient.prompt().user(fullPrompt).call().content();
            if (resp == null) return 0;

            // 【主路径】BeanOutputConverter结构化解析
            try {
                EvalScore result = converter.convert(resp);
                return Math.max(0, Math.min(1, result.score()));
            } catch (Exception convertEx) {
                // 【fallback】回退原有正则数字提取
                log.debug("[Eval] BeanOutputConverter解析失败，回退正则提取: {}",
                        convertEx.getMessage());
                resp = resp.trim().replaceAll("[^0-9.]", "");
                double score = Double.parseDouble(resp);
                return Math.max(0, Math.min(1, score));
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
