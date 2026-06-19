package io.codex.haloaiassistant.config;

import lombok.Data;

@Data
public class AiAssistantSetting {
    private String apiEndpoint = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private Integer maxTokens = 2048;
    private String systemPrompt = "你是一个智能博客助手，可以帮助用户管理博客。";
}
