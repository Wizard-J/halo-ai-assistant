package io.codex.haloaiassistant.config;

import lombok.Data;

@Data
public class AiAssistantSetting {
    // AI 配置
    private String apiEndpoint = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-v4-flash";
    private Integer maxTokens = 32768;
    private String systemPrompt = "你是一个智能博客助手，可以帮助用户管理博客。";

    // 推送配置
    private String pushSecret = "";
    private String pushChannel = "serverchan";
    private String pushToken = "";
    private String pushTime = "today";

    // 页面显示配置
    private String pageTitle = "老巫师";
    private String pageIcon = "🧙";
    private String greeting = "你好，我是老巫师，巫师前沿站的AI助手";
}
