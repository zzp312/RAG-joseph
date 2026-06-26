package com.xushu.rag.controller;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.PageResult;
import com.xushu.rag.common.ResultUtils;
import com.xushu.rag.pojo.dto.WordFrequencyPageQueryDTO;
import com.xushu.rag.scheduled.TaskJobScheduled;
import com.xushu.rag.service.WordFrequencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Title: WordFrequencyController
 * @Author Xushu
 * @Package com.Xushu.rag.controller
 * @description: 分词统计控制器
 */

@Tag(name="WordFrequencyController",description = "分词统计控制器")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/frequency")
public class WordFrequencyController {
    @Autowired
    private WordFrequencyService wordFrequencyService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskJobScheduled taskJobScheduled;


    // 分页条件查询
    @PostMapping("/page")
    @Operation(summary = "page", description = "分页查询")
    public BaseResponse<PageResult> pageQuery(@RequestBody WordFrequencyPageQueryDTO queryDTO) {
        PageResult pageResult = wordFrequencyService.pageQuery(queryDTO);
        pageResult.setTotal(pageResult.getRecords().size());
        return ResultUtils.success(pageResult);
    }

    // 清空数据
    @DeleteMapping("/clean")
    @Operation(summary = "clean", description = "清空数据")
    public BaseResponse<String> clean() {
        wordFrequencyService.remove(null);
        return ResultUtils.success("清空成功");
    }

    // 手动触发热词统计
    @PostMapping("/trigger")
    @Operation(summary = "trigger", description = "手动触发热词统计")
    public BaseResponse<String> trigger() {
        try {
            taskJobScheduled.taskJob();
            return ResultUtils.success("热词统计任务已触发执行");
        } catch (Exception e) {
            log.error("手动触发热词统计失败", e);
            return ResultUtils.error("触发失败: " + e.getMessage());
        }
    }





    @GetMapping("/getList")
    public BaseResponse<Object> getList() throws JsonProcessingException {
        String cacheKey = "wordFrequencyList";
        String cachedListStr = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cachedListStr != null) {
            try {
                Object cachedList = objectMapper.readValue(cachedListStr, List.class);
                log.info("从 Redis 缓存中获取数据");
                return ResultUtils.success(cachedList);
            } catch (Exception e) {
                log.error("Redis 缓存解析失败", e);
            }
        }

        Object dataList = wordFrequencyService.list();

        String dataListJson = objectMapper.writeValueAsString(dataList);
        redisTemplate.opsForValue().set(cacheKey, dataListJson);
        redisTemplate.expire(cacheKey, 5, TimeUnit.MINUTES);

        return ResultUtils.success(dataList);
    }




}
