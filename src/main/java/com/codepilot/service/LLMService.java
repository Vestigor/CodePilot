package com.codepilot.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.codepilot.model.ModelConfig;
import com.codepilot.util.ConfigManager;
import com.codepilot.util.StreamingOutputHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import io.reactivex.Flowable;

import java.util.Arrays;
import java.util.List;

@Service(Service.Level.PROJECT)
public final class LLMService {
    private static final Logger LOG = Logger.getInstance(LLMService.class);
    private final Project project;
    private final ConfigManager configManager;

    // 构造方法，初始化项目和配置管理器
    public LLMService(Project project) {
        this.project = project;
        this.configManager = ApplicationManager.getApplication().getService(ConfigManager.class);
    }

    // 获取LLMService实例
    public static LLMService getInstance(Project project) {
        return project.getService(LLMService.class);
    }

    // 生成流式响应
    public void generateStreamingResponse(String prompt, StreamingOutputHandler handler) {
        try {
            // 创建Generation实例
            Generation gen = new Generation();
            // 创建用户消息
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 获取当前模型配置
            ModelConfig modelConfig = configManager.getCurrentModelConfig();
            // 设置生成参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(configManager.getApiKey())
                    .model(modelConfig.getName())
                    .incrementalOutput(true)
                    .resultFormat("message")
                    .messages(Arrays.asList(userMsg))
                    .build();

            // 发起流式调用，获取结果
            Flowable<GenerationResult> result = gen.streamCall(param);
            result.blockingForEach(message -> {
                // 如果处理被取消，停止处理
                if (handler.isCancelled()) {
                    return;
                }

                // 提取响应内容
                String content = message.getOutput()
                        .getChoices()
                        .get(0)
                        .getMessage()
                        .getContent();

                // 如果内容不为空，则将其追加到输出
                if (content != null && !content.isEmpty()) {
                    handler.appendToken(content);
                }
            });
        } catch (Exception e) {
            // 记录错误日志并向输出处理器添加错误信息
            LOG.error("Failed to generate streaming response", e);
            handler.appendLine("\n[Error] Failed to generate response: " + e.getMessage());
        }
    }

    // 生成常规响应
    public String generateResponse(String prompt) {
        try {
            // 创建Generation实例
            Generation gen = new Generation();
            // 创建用户消息
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 获取当前模型配置
            ModelConfig modelConfig = configManager.getCurrentModelConfig();
            // 设置生成参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(configManager.getApiKey())
                    .model(modelConfig.getName())
                    .resultFormat("message")
                    .messages(Arrays.asList(userMsg))
                    .build();

            // 获取生成的结果
            GenerationResult result = gen.call(param);
            return result.getOutput()
                    .getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();
        } catch (Exception e) {
            // 记录错误日志并返回错误信息
            LOG.error("Failed to generate response", e);
            return "[Error] Failed to generate response: " + e.getMessage();
        }
    }

    // 使用消息历史生成流式响应
    public void generateResponseWithHistory(List<Message> messages, StreamingOutputHandler handler) {
        try {
            // 创建Generation实例
            Generation gen = new Generation();
            // 获取当前模型配置
            ModelConfig modelConfig = configManager.getCurrentModelConfig();

            // 设置生成参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(configManager.getApiKey())
                    .model(modelConfig.getName())
                    .incrementalOutput(true)
                    .resultFormat("message")
                    .messages(messages)
                    .build();

            // 发起流式调用，获取结果
            Flowable<GenerationResult> result = gen.streamCall(param);
            result.blockingForEach(message -> {
                // 如果处理被取消，停止处理
                if (handler.isCancelled()) {
                    return;
                }

                // 提取响应内容
                String content = message.getOutput()
                        .getChoices()
                        .get(0)
                        .getMessage()
                        .getContent();

                // 如果内容不为空，则将其追加到输出
                if (content != null && !content.isEmpty()) {
                    handler.appendToken(content);
                }
            });
        } catch (Exception e) {
            // 记录错误日志并向输出处理器添加错误信息
            LOG.error("Failed to generate response with history", e);
            handler.appendLine("\n[Error] Failed to generate response: " + e.getMessage());
        }
    }
}