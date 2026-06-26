package com.xushu.rag.aop;

import com.xushu.rag.annotation.Loggable;
import com.xushu.rag.entity.LogInfo;
import com.xushu.rag.service.LogInfoService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Aspect
@Component
public class LoggingAspect {

    @Autowired
    private LogInfoService logInfoService;
    @Pointcut("@annotation(loggable)")
    public void loggableMethods(com.xushu.rag.annotation.Loggable loggable) {
    }

    @Before(value = "loggableMethods(loggable)", argNames = "joinPoint,loggable")
    public void logBefore(JoinPoint joinPoint, Loggable  loggable) {
        LogInfo logInfo = new LogInfo();
        logInfo.setMethodName(joinPoint.getSignature().getName());
        logInfo.setClassName(joinPoint.getTarget().getClass().getName());
        logInfo.setRequestTime(new Date());




        // 获取参数值
        Object[] args = joinPoint.getArgs();

        // 判断是否指定了具体的参数名
        if (loggable.value() != null && loggable.value().length() > 0) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            // 获取方法参数名
            String[] parameterNames = signature.getParameterNames();

            Map<String, Object> selectedParams = new HashMap<>();
            // 将注解里的参数名数组转为 List 方便判断
            List<String> targetParams = Arrays.asList(loggable.value());

            if (parameterNames != null) {
                for (int i = 0; i < parameterNames.length; i++) {
                    // 如果当前参数名在注解配置的名单里，就记录下来
                    if (targetParams.contains(parameterNames[i])) {
                        selectedParams.put(parameterNames[i], args[i]);
                    }
                }
            }
            // 保存筛选后的参数 (建议使用 JSON.toJSONString(selectedParams))
            logInfo.setRequestParams(selectedParams.toString());
            // --- 补充的核心逻辑结束 ---
        } else {
            // 如果没有指定特定参数，则记录所有参数
            logInfo.setRequestParams(Arrays.toString(args));
        }



        logInfoService.save(logInfo);
    }
}
    