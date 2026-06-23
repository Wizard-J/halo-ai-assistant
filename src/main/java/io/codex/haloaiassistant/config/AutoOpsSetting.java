package io.codex.haloaiassistant.config;

import lombok.Data;

@Data
public class AutoOpsSetting {
    // === 第一人物：巫师前沿站 ===
    private Boolean enabled = false;
    private Boolean autoPublish = true;
    private String runTime = "08:30";
    private String timezone = "Asia/Shanghai";
    private String authorName = "巫师前沿站";
    private String authorUsername = "wizard-frontier";
    private String primaryRssSources = "https://hnrss.org/frontpage\n"
            + "https://feeds.arstechnica.com/arstechnica/index\n"
            + "https://www.infoq.com/feed/\n"
            + "https://news.ycombinator.com/rss";
    private String rssSources = "https://github.blog/changelog/feed/\n"
            + "https://blog.cloudflare.com/rss/\n"
            + "https://openai.com/news/rss.xml\n"
            + "https://blog.google/technology/ai/rss/";
    private String topics = "人工智能, 大模型, 开源软件, 云计算, 开发工具, 网络安全";
    private String defaultTags = "AI前沿";
    private Integer maxNewsItems = 8;
    private Integer maxTokens = 3000;

    // === 第二人物：书虫漫步 ===
    private Boolean secondaryEnabled = false;
    private String secondaryAuthorName = "书虫漫步";
    private String secondaryAuthorUsername = "bookworm-wanderer";
    private String secondaryRssSources = "https://www.themarginalian.org/feed/\n"
            + "https://www.newyorker.com/feed/books\n"
            + "https://www.theguardian.com/books/rss\n"
            + "https://aeon.co/feed.rss\n"
            + "https://lithub.com/feed/";
    private String secondaryTopics = "人物传记, 书评, 人文思想, 历史, 哲学, 文学";
    private String secondaryTags = "人物传记, 每日好书";
    private Integer secondaryMaxNewsItems = 5;
    private Integer secondaryMaxTokens = 3000;

    // === 第三人物：技术干货 ===
    private Boolean tertiaryEnabled = false;
    private String tertiaryAuthorName = "技术猎手";
    private String tertiaryAuthorUsername = "tech-hunter";
    private String tertiaryRssSources = "http://highscalability.com/blog/atom.xml\n"
            + "https://martinfowler.com/feed.atom\n"
            + "https://blog.pragmaticengineer.com/rss/\n"
            + "https://blog.bytebytego.com/feed\n"
            + "https://blog.quastor.org/feed.xml";
    private String tertiaryTopics = "系统设计, 数据结构与算法, 后端架构, 性能优化, 最佳实践, 开源项目";
    private String tertiaryTags = "技术干货, 优质译文";
    private Integer tertiaryMaxNewsItems = 5;
    private Integer tertiaryMaxTokens = 3000;
}
