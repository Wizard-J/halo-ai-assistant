package io.codex.haloaiassistant.autoops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.tools.ArticleTool;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import io.codex.haloaiassistant.config.AutoOpsSetting;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.HashMap;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** Real local RSS retrieval, simulated AI and Halo writes. No production or subscription calls. */
class AutoOpsSchedulingTest {
    HttpServer rss;
    AutoOpsService service;
    AutoOpsSetting auto;
    AiAssistantSetting basic;
    ConfigMap state;
    AgentService agent;
    ArticleTool.CreateArticleTool articles;
    ReactiveExtensionClient storage;

    @BeforeEach void setup() throws Exception {
        rss = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rss.createContext("/rss", exchange -> {
            byte[] body = ("<rss><channel><item><title>测试资讯</title>"
                    + "<link>https://example.com/news</link><description>内容摘要</description>"
                    + "</item></channel></rss>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        rss.start();
        storage = mock(ReactiveExtensionClient.class);
        agent = mock(AgentService.class);
        articles = mock(ArticleTool.CreateArticleTool.class);
        state = new ConfigMap();
        state.setData(new HashMap<>());
        when(storage.fetch(eq(ConfigMap.class), anyString())).thenReturn(Mono.just(state));
        when(storage.fetch(eq(User.class), anyString())).thenReturn(Mono.empty());
        doAnswer(call -> Mono.just(call.getArgument(0))).when(storage).create(any());
        doAnswer(call -> Mono.just(call.getArgument(0))).when(storage).update(any());
        when(articles.executeInternal(any())).thenReturn("文章创建成功！");
        when(articles.execute(any())).thenReturn("待确认草稿");
        String content = "测试正文。".repeat(80) + "\n来源：https://example.com/news";
        when(agent.generateText(any(), anyString(), anyString(), anyInt()))
                .thenReturn(Mono.just("{\"title\":\"测试自动文章\",\"content\":\"" + content.replace("\n", "\\n") + "\"}"));
        service = new AutoOpsService(mock(ReactiveSettingFetcher.class), storage, agent, articles);
        auto = new AutoOpsSetting();
        auto.setEnabled(true);
        auto.setRunTime("00:00");
        auto.setTimezone("UTC");
        auto.setPrimaryRssSources("http://127.0.0.1:" + rss.getAddress().getPort() + "/rss");
        auto.setRssSources("");
        basic = new AiAssistantSetting();
        basic.setProvider("chatgpt"); // No API key: subscription is a valid automation provider.
    }

    @AfterEach void stop() { rss.stop(0); }

    @Test void enabledSchedulePublishesOnceWithoutConfirmationAndSupportsAllPipelines() {
        auto.setAutoPublish(true);
        auto.setSecondaryEnabled(true);
        auto.setSecondaryRssSources(auto.getPrimaryRssSources());
        auto.setTertiaryEnabled(true);
        auto.setTertiaryRssSources(auto.getPrimaryRssSources());
        service.runIfDue(auto, basic);
        service.runIfDue(auto, basic);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles, times(3)).executeInternal(args.capture());
        assertTrue(args.getAllValues().stream().allMatch(a -> "published".equals(a.path("status").asText())));
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(agent, times(3)).generateText(any(), anyString(), prompts.capture(), anyInt());
        assertTrue(prompts.getAllValues().stream().allMatch(prompt -> prompt.contains("原始 URL")));
        assertTrue(prompts.getAllValues().get(1).contains("仅当候选明确涉及人物或书籍"));
        verify(articles, never()).execute(any());
        assertTrue(state.getData().get("processedUrls").contains("example.com/news"));
        assertTrue(state.getData().containsKey("secondaryprocessedUrls"));
        assertTrue(state.getData().containsKey("tertiaryprocessedUrls"));
    }

    @Test void scheduledPublishCreatesRealPostAndReleaseSnapshotThroughExistingWriter() {
        when(storage.get(eq(User.class), anyString())).thenReturn(Mono.just(new User()));
        var writer = new ArticleTool.CreateArticleTool(storage);
        service = new AutoOpsService(mock(ReactiveSettingFetcher.class), storage, agent, writer);
        auto.setAutoPublish(true);
        service.runIfDue(auto, basic);
        var posts = ArgumentCaptor.forClass(Post.class);
        var snapshots = ArgumentCaptor.forClass(Snapshot.class);
        verify(storage).update(posts.capture());
        verify(storage).create(snapshots.capture());
        Post post = posts.getValue();
        assertTrue(post.getSpec().getPublish());
        assertEquals("wizard-frontier", post.getSpec().getOwner());
        assertEquals(snapshots.getValue().getMetadata().getName(), post.getSpec().getReleaseSnapshot());
        assertEquals(post.getSpec().getHeadSnapshot(), post.getSpec().getReleaseSnapshot());
        assertTrue(state.getData().get("processedUrls").contains("example.com/news"));
    }

    @Test void autoPublishOffSavesDraftAutomatically() {
        auto.setAutoPublish(false);
        service.runIfDue(auto, basic);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles).executeInternal(args.capture());
        assertEquals("draft", args.getValue().path("status").asText());
    }

    @Test void failedQualityCheckDowngradesAutomaticPublishToSavedDraft() {
        service.publishArticle("测试文章", "太短", "", "author", ZonedDateTime.now(), true, "system", true);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles).executeInternal(args.capture());
        assertEquals("draft", args.getValue().path("status").asText());
        assertTrue(args.getValue().path("content").asText().contains("质量检查未通过"));
    }

    @Test void generationFailureDoesNotWriteOrMarkProcessedOrRetryEveryMinute() {
        when(agent.generateText(any(), anyString(), anyString(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("订阅额度不足")));
        service.runIfDue(auto, basic);
        service.runIfDue(auto, basic);
        verify(agent).generateText(any(), anyString(), anyString(), anyInt());
        verifyNoInteractions(articles);
        assertFalse(state.getData().containsKey("processedUrls"));
        assertTrue(state.getData().get("lastRunResult").contains("订阅额度不足"));
    }

    @Test void failedWriteDoesNotMarkNewsProcessed() {
        when(articles.executeInternal(any())).thenReturn("[错误] 写入失败");
        service.runIfDue(auto, basic);
        assertFalse(state.getData().containsKey("processedUrls"));
        assertTrue(state.getData().get("lastRunResult").contains("写入失败"));
    }

    @Test void disabledOrNotDueDoesNotRun() {
        auto.setEnabled(false);
        service.runIfDue(auto, basic);
        auto.setEnabled(true);
        auto.setRunTime("23:59:59.999999999");
        service.runIfDue(auto, basic);
        verifyNoInteractions(agent, articles);
    }

    @Test void statePersistenceFailureStopsBeforePaidGeneration() {
        doReturn(Mono.error(new IllegalStateException("storage unavailable"))).when(storage).update(any());
        assertThrows(IllegalStateException.class, () -> service.runIfDue(auto, basic));
        verifyNoInteractions(agent, articles);
    }

    @Test void manualTestStillCreatesConfirmationInsteadOfWriting() {
        service.publishArticle("测试文章", "正文。".repeat(150), "", "author", ZonedDateTime.now(), false, "admin", false);
        verify(articles).execute(any());
        verify(articles, never()).executeInternal(any());
    }
}
