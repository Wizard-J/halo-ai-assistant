package io.codex.haloaiassistant.config;

import lombok.Data;

@Data
public class AiAssistantSetting {
    // AI 配置
    private String apiEndpoint = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private Integer maxTokens = 2048;
    private String systemPrompt = "你是一个智能博客助手，可以帮助用户管理博客。";

    // 推送配置
    private String pushSecret = "";
    private String pushChannel = "serverchan";
    private String pushToken = "";
    private String pushTime = "today";

    // 页面显示配置
    private String pageTitle = "AI 智能助手";
    private String pageIcon = "🧠";
    private String greeting = "你好，我是 Halo AI 智能助手";
}
