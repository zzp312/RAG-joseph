package com.xushu.rag.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.*;

/**
 * @deprecated 自2026-06起由 JavaDocumentParser 替代，仅保留作为非PDF文件的备用解析方案。
 * Phase 3后将完全移除Python依赖。
 */
@Deprecated
@Slf4j
@Component
public class PythonScriptExecutor {

    @Value("${python.script.path:src/main/resources/tools/python}")
    private String scriptPath;

    @Value("${python.executable:python}")
    private String pythonExecutable;

    @Value("${python.timeout.seconds:120}")
    private int timeoutSeconds;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    public JSONObject executeParser(String filePath) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        String scriptFile = scriptPath + File.separator + "document_parser.py";
        return executeScript(scriptFile, filePath);
    }

    public JSONObject executeSplitter(String inputFile, String outputFile, int chunkSize, int chunkOverlap)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        String scriptFile = scriptPath + File.separator + "text_splitter.py";
        return executeScript(scriptFile, inputFile, outputFile, String.valueOf(chunkSize), String.valueOf(chunkOverlap));
    }

    private JSONObject executeScript(String scriptFile, String... args)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        ProcessBuilder processBuilder = new ProcessBuilder();

        // 解析脚本绝对路径（支持classpath资源和文件系统路径）
        File script = resolveScriptFile(scriptFile);
        String scriptAbsPath = script.getAbsolutePath();
        File workingDir = script.getParentFile();

        processBuilder.environment().put("PYTHONPATH", workingDir != null ? workingDir.getAbsolutePath() : "");

        String[] command = new String[args.length + 2];
        command[0] = pythonExecutable;
        command[1] = scriptAbsPath;
        System.arraycopy(args, 0, command, 2, args.length);

        processBuilder.command(command);
        if (workingDir != null && workingDir.exists()) {
            processBuilder.directory(workingDir);
        }

        log.info("执行Python脚本: {}, 工作目录: {}", scriptAbsPath, workingDir);
        Process process = processBuilder.start();

        Future<String> future = executorService.submit(() -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            return output.toString();
        });

        Future<String> errorFuture = executorService.submit(() -> {
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
            }
            return error.toString();
        });

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new TimeoutException("Python script execution timed out");
        }

        String output = future.get(timeoutSeconds, TimeUnit.SECONDS);
        String error = errorFuture.get(timeoutSeconds, TimeUnit.SECONDS);

        if (!error.isEmpty()) {
            log.warn("Python script error output: {}", error);
        }

        if (output.isEmpty()) {
            throw new IOException("Python script returned empty output");
        }

        try {
            return JSON.parseObject(output);
        } catch (Exception e) {
            log.error("Failed to parse Python script output: {}", output, e);
            throw new IOException("Failed to parse script output", e);
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * 解析脚本文件路径，支持文件系统路径和classpath资源路径
     * @author Joseph
     */
    private File resolveScriptFile(String scriptFile) throws IOException {
        // 先尝试文件系统路径（相对路径基于工作目录解析）
        File file = new File(scriptFile).getAbsoluteFile();
        if (file.exists()) {
            return file;
        }

        // 尝试从classpath加载（打包后jar内的资源）
        String resourcePath = scriptFile.replace("\\", "/");
        if (resourcePath.startsWith("src/main/resources/")) {
            resourcePath = resourcePath.substring("src/main/resources/".length());
        }
        try {
            ClassPathResource classPathResource = new ClassPathResource(resourcePath);
            if (classPathResource.exists()) {
                // 将classpath资源复制到临时文件
                File tempScript = File.createTempFile("python_script_", ".py");
                tempScript.deleteOnExit();
                try (java.io.InputStream is = classPathResource.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(tempScript)) {
                    is.transferTo(fos);
                }
                return tempScript;
            }
        } catch (Exception e) {
            log.warn("从classpath加载脚本失败: {}", resourcePath, e);
        }

        // 返回绝对路径，让ProcessBuilder报出明确错误
        return file;
    }
}
