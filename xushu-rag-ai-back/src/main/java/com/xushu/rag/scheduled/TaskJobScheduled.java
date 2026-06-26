package com.xushu.rag.scheduled;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xushu.rag.entity.LogInfo;
import com.xushu.rag.entity.WordFrequency;
import com.xushu.rag.service.LogInfoService;
import com.xushu.rag.service.WordFrequencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Title: TaskJobScheduled
 * @Author Xushu
 * @Package com.Xushu.rag.scheduled
 * @Date 2025/3/6 15:13
 * @description: 分词器定时任务
 */

@Component("taskJob")
@Slf4j
public class TaskJobScheduled {

    @Autowired
    private LogInfoService logInfoService;

    @Autowired
    private WordFrequencyService wordFrequencyService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;



    /**
 * 定时任务调度注解，用于标记需要定时执行的方法
 *
 * cron表达式说明：
 * - 第1位：秒（0表示第0秒）
 * - 第2位：分钟（0表示第0分钟）
 * - 第3位：小时（*表示每小时）
 * - 第4位：日期（*表示每日）
 * - 第5位：月份（*表示每月）
 * - 第6位：星期（?表示不指定具体星期）
 *
 * 此注解配置的定时任务执行规则为：每小时的0分0秒执行一次
 */
@Scheduled(cron = "0 0 * * * ?")

    public void taskJob() {
        log.info("分词器定时任务开始执行");
        redisTemplate.delete("wordFrequencyList");
        // 获取所有的热词数据
        List<WordFrequency> wordFrequencies = wordFrequencyService.list();
        // 转为 Map key为word value为WordFrequency  判断是否存在
        Map<String, List<WordFrequency>> collectMap = wordFrequencies.stream().collect(Collectors.groupingBy(WordFrequency::getWord));
        StringBuilder text = new StringBuilder();
        // 获取所有日志
        List<LogInfo> list = logInfoService.list((Wrapper<LogInfo>) null);
        for (LogInfo logInfo : list){
            // 将日志中的参数转为字符串
            text.append(logInfo.getRequestParams());
        }
        String result = text.toString();
        // 分词
        Map<String, WordFrequency> newMap= new HashMap<>();
        try (StringReader reader = new StringReader(result)) {
            IKSegmenter segment = new IKSegmenter(reader, true);
            Lexeme lexeme;
            List<WordFrequency> wordFrequencyList = new ArrayList<>();
            List<WordFrequency> updateList = new ArrayList<>();
            while ((lexeme = segment.next()) != null) {
                // 只有一个字
                if (lexeme.getLength() <= 1){
                    continue;
                }
                // 字符过长不统计， 没有意义
                if (lexeme.getLength() >=10){
                    continue;
                }
                // 不存在的是新热词
                if (!collectMap.containsKey(lexeme.getLexemeText())){
                    // 防止重复新增
                    if(newMap.containsKey(lexeme.getLexemeText())){
                        WordFrequency wordFrequency = newMap.get(lexeme.getLexemeText());
                        wordFrequency.setCountNum(wordFrequency.getCountNum()+1);
                    }
                    else {
                        WordFrequency wordFrequency = new WordFrequency();
                        wordFrequency.setWord(lexeme.getLexemeText());
                        wordFrequency.setCountNum(1);
                        wordFrequency.setBusinessType("log");
                        wordFrequency.setCreateTime(new Date());
                        wordFrequency.setUpdateTime(new Date());
                        wordFrequencyList.add(wordFrequency);
                        newMap.put(lexeme.getLexemeText(), wordFrequency);
                    }
                // 存在的修改数量+1
                }else if (collectMap.containsKey(lexeme.getLexemeText())){
                    WordFrequency wordFrequency = collectMap.get(lexeme.getLexemeText()).get(0);
                    wordFrequency.setCountNum(wordFrequency.getCountNum()+1);
                    wordFrequency.setUpdateTime(new Date());
                    updateList.add(wordFrequency);
                }
            }
            // 新增新热词、修改老热词数量
            wordFrequencyService.saveBatch(wordFrequencyList);
            wordFrequencyService.saveOrUpdateBatch(updateList);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
