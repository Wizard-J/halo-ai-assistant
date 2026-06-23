package io.codex.haloaiassistant.config;

import io.codex.haloaiassistant.agent.tools.ArticleTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 独立配置类 — 避免循环依赖。
 * CreateArticleTool 是 ArticleTool 的静态内部类，需要显式注册为 Bean。
 */
@Configuration
public class ArticleToolConfig {

    @Bean
    ArticleTool.CreateArticleTool createArticleTool(ReactiveExtensionClient client) {
        return new ArticleTool.CreateArticleTool(client);
    }
}
