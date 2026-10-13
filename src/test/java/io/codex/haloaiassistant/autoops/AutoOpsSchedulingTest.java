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
    String sourceUrl;
    String sourceHtml;
    static final String CLAIM_ONE = "测试系统采用分批处理来降低峰值内存";
    static final String CLAIM_TWO = "失败批次通过检查点恢复而不是全部重跑";
    static final String DETAIL_ONE = "示例将一百条记录拆分成十个批次处理";
    static final String DETAIL_TWO = "第三批失败后从第三批重新执行后续任务";

    @BeforeEach void setup() throws Exception {
        rss = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceUrl = "http://127.0.0.1:" + rss.getAddress().getPort() + "/article";
        sourceHtml = "<article><p>" + CLAIM_ONE + "。" + CLAIM_TWO + "。" + "这是本地合成测试材料的详细背景。".repeat(25)
                + "</p><p>" + DETAIL_ONE + "。" + "各批次保存自己的进度与检查点以支持独立恢复。".repeat(20)
                + "</p><p>" + DETAIL_TWO + "。" + "实验仅用于检验正文传递，不能推断真实系统性能。".repeat(20) + "</p></article>";
        rss.createContext("/article", exchange -> {
            byte[] body = sourceHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        rss.createContext("/rss", exchange -> {
            byte[] body = ("<rss><channel><item><title>测试资讯</title>"
                    + "<link>" + sourceUrl + "</link><description>仅供发现的摘要，不得扩写</description>"
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
        String content = CLAIM_ONE + "。" + CLAIM_TWO + "。" + DETAIL_ONE + "。" + DETAIL_TWO + "。"
                + "测试正文的分析与边界。".repeat(90) + "\n来源：" + sourceUrl;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var review = mapper.createObjectNode().put("passed", true);
        review.putArray("issues");
        for (String group : java.util.List.of("claims", "details")) {
            var entries = review.putArray(group);
            for (String claim : group.equals("claims") ? java.util.List.of(CLAIM_ONE, CLAIM_TWO) : java.util.List.of(DETAIL_ONE, DETAIL_TWO))
                entries.addObject().put("claim", claim).put("sourceUrl", sourceUrl).put("evidence", claim);
        }
        when(agent.generateText(any(), anyString(), anyString(), anyInt()))
                .thenAnswer(call -> Mono.just(call.getArgument(1, String.class).contains("严格的来源核验编辑")
                        ? review.toString() : mapper.createObjectNode().put("title", "测试自动文章").put("content", content).toString()));
        service = new AutoOpsService(mock(ReactiveSettingFetcher.class), storage, agent, articles, new SourceArticleReader(true));
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
        verify(agent, times(6)).generateText(any(), anyString(), prompts.capture(), anyInt());
        assertTrue(prompts.getAllValues().stream().allMatch(prompt -> prompt.contains("原始 URL")));
        assertTrue(prompts.getAllValues().get(2).contains("仅当候选明确涉及人物或书籍"));
        assertTrue(prompts.getAllValues().stream().allMatch(prompt -> prompt.contains(DETAIL_TWO)));
        assertTrue(prompts.getAllValues().stream().noneMatch(prompt -> prompt.contains("仅供发现的摘要")));
        verify(articles, never()).execute(any());
        assertTrue(state.getData().get("processedUrls").contains(sourceUrl));
        assertTrue(state.getData().containsKey("secondaryprocessedUrls"));
        assertTrue(state.getData().containsKey("tertiaryprocessedUrls"));
    }

    @Test void scheduledPublishCreatesRealPostAndReleaseSnapshotThroughExistingWriter() {
        when(storage.get(eq(User.class), anyString())).thenReturn(Mono.just(new User()));
        var writer = new ArticleTool.CreateArticleTool(storage);
        service = new AutoOpsService(mock(ReactiveSettingFetcher.class), storage, agent, writer, new SourceArticleReader(true));
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
        assertTrue(state.getData().get("processedUrls").contains(sourceUrl));
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

    @Test void summaryOnlyCandidateNeverCallsModelOrWriter() {
        sourceHtml = "<article><p>只有一小段摘要。</p></article>";
        service.runIfDue(auto, basic);
        verifyNoInteractions(agent, articles);
        assertFalse(state.getData().containsKey("processedUrls"));
        assertTrue(state.getData().get("lastRunResult").contains("停止生成"));
    }

    @Test void invalidGeneratedJsonNeverWrites() {
        when(agent.generateText(any(), anyString(), anyString(), anyInt())).thenReturn(Mono.just("模型错误文本"));
        service.runIfDue(auto, basic);
        verifyNoInteractions(articles);
        assertFalse(state.getData().containsKey("processedUrls"));
    }

    @Test void atomUsesArticleLinkInsteadOfSelfFeedLink() {
        rss.createContext("/atom", exchange -> {
            byte[] body = ("<feed><entry><title>测试 Atom 正文</title><link rel='self' href='http://127.0.0.1:1/not-article'/>"
                    + "<link href='" + sourceUrl + "'/><summary>摘要</summary></entry></feed>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        auto.setPrimaryRssSources("http://127.0.0.1:" + rss.getAddress().getPort() + "/atom");
        service.runIfDue(auto, basic);
        verify(articles).executeInternal(any());
        assertTrue(state.getData().get("processedUrls").contains(sourceUrl));
        assertFalse(state.getData().get("processedUrls").contains("not-article"));
    }

    @Test void bookwormTriesLaterCandidateWhenNewestBodyUnavailable() {
        rss.createContext("/fallback", exchange -> {
            byte[] body = ("<rss><channel><item><title>不可读的新文章</title><link>" + sourceUrl.replace("/article", "/missing") + "</link></item>"
                    + "<item><title>有正文的后续文章</title><link>" + sourceUrl + "</link></item></channel></rss>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        auto.setPrimaryRssSources("");
        auto.setSecondaryEnabled(true);
        auto.setSecondaryMaxNewsItems(1);
        auto.setSecondaryRssSources("http://127.0.0.1:" + rss.getAddress().getPort() + "/fallback");
        service.runIfDue(auto, basic);
        verify(articles).executeInternal(any());
        assertTrue(state.getData().get("secondaryprocessedUrls").contains(sourceUrl));
        assertFalse(state.getData().get("secondaryprocessedUrls").contains("missing"));
    }

    @Test void candidatesNotCitedByGeneratedArticleRemainUnprocessed() {
        String unusedUrl = sourceUrl.replace("/article", "/unused");
        rss.createContext("/unused", exchange -> {
            byte[] body = sourceHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        rss.createContext("/choices", exchange -> {
            byte[] body = ("<rss><channel><item><title>被采用文章</title><link>" + sourceUrl + "</link></item>"
                    + "<item><title>未采用文章</title><link>" + unusedUrl + "</link></item></channel></rss>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        auto.setPrimaryRssSources("");
        auto.setSecondaryEnabled(true);
        auto.setSecondaryMaxNewsItems(2);
        auto.setSecondaryRssSources("http://127.0.0.1:" + rss.getAddress().getPort() + "/choices");
        service.runIfDue(auto, basic);
        verify(articles).executeInternal(any());
        assertTrue(state.getData().get("secondaryprocessedUrls").contains(sourceUrl));
        assertFalse(state.getData().get("secondaryprocessedUrls").contains(unusedUrl));
    }

    @Test void brokenEditorialReviewForcesDraftEvenWhenAutoPublishOn() {
        auto.setAutoPublish(true);
        when(agent.generateText(any(), contains("严格的来源核验编辑"), anyString(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("审核服务不可用")));
        service.runIfDue(auto, basic);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles).executeInternal(args.capture());
        assertEquals("draft", args.getValue().path("status").asText());
        assertTrue(args.getValue().path("content").asText().contains("必须人工复核"));
    }

    @Test void reviewCannotPassWithInventedEvidence() {
        auto.setAutoPublish(true);
        when(agent.generateText(any(), contains("严格的来源核验编辑"), anyString(), anyInt()))
                .thenReturn(Mono.just("{\"passed\":true,\"issues\":[],\"claims\":["
                        + "{\"claim\":\"" + CLAIM_ONE + "\",\"sourceUrl\":\"" + sourceUrl
                        + "\",\"evidence\":\"这是一段正文中完全不存在的审核证据文本\"}],\"details\":[]}"));
        service.runIfDue(auto, basic);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles).executeInternal(args.capture());
        assertEquals("draft", args.getValue().path("status").asText());
        assertTrue(args.getValue().path("content").asText().contains("找不到审核证据"));
    }

    @Test void explicitEditorialRejectionForcesDraft() {
        auto.setAutoPublish(true);
        when(agent.generateText(any(), contains("严格的来源核验编辑"), anyString(), anyInt()))
                .thenReturn(Mono.just("{\"passed\":false,\"issues\":[\"通用感悟缺乏原文论证\"],\"claims\":[],\"details\":[]}"));
        service.runIfDue(auto, basic);
        var args = ArgumentCaptor.forClass(JsonNode.class);
        verify(articles).executeInternal(args.capture());
        assertEquals("draft", args.getValue().path("status").asText());
        assertTrue(args.getValue().path("content").asText().contains("通用感悟"));
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
