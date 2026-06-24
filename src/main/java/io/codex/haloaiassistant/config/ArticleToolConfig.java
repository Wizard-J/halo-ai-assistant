package io.codex.haloaiassistant.config;

import io.codex.haloaiassistant.agent.tools.ArticleTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 独立配置类 — 避免循环依赖。
 * 显式注册需要手工注入的工具 Bean。
 */
@Configuration
public class ArticleToolConfig {

    @Bean
    ArticleTool.CreateArticleTool createArticleTool(ReactiveExtensionClient client) {
        return new ArticleTool.CreateArticleTool(client);
    }

    @Bean
    ArticleTool.BatchTagArticlesTool batchTagArticlesTool(ReactiveExtensionClient client) {
        return new ArticleTool.BatchTagArticlesTool(client);
    }

    @Bean
    ArticleTool.AutoTagArticlesTool autoTagArticlesTool(
            ReactiveExtensionClient client, ReactiveSettingFetcher settingFetcher) {
        return new ArticleTool.AutoTagArticlesTool(client, settingFetcher);
    }
}
