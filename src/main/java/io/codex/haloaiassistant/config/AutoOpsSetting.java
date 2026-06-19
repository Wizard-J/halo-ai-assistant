package io.codex.haloaiassistant.config;

import lombok.Data;

@Data
public class AutoOpsSetting {
    private Boolean enabled = false;
    private Boolean autoPublish = true;
    private String runTime = "08:30";
    private String timezone = "Asia/Shanghai";
    private String authorName = "巫师前沿站";
    private String authorUsername = "wizard-frontier";
    private String rssSources = "https://github.blog/changelog/feed/\n"
            + "https://blog.cloudflare.com/rss/\n"
            + "https://openai.com/news/rss.xml\n"
            + "https://blog.google/technology/ai/rss/";
    private String topics = "人工智能, 大模型, 开源软件, 云计算, 开发工具, 网络安全";
    private Integer maxNewsItems = 8;
    private Integer maxTokens = 3000;
}
