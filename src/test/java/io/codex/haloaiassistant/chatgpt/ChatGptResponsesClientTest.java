package io.codex.haloaiassistant.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.AgentService.ChatMessage;
import io.codex.haloaiassistant.agent.Tool;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import io.codex.haloaiassistant.agent.confirmation.SpringContextBridge;
import io.codex.haloaiassistant.agent.tools.ArticleTool;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.ListResult;
import run.halo.app.core.extension.content.Snapshot;

/** Uses real HTTP/SSE decoding; OpenAI and Halo storage are simulated, no subscription is consumed. */
class ChatGptResponsesClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentLinkedQueue<String> streams = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger modelHits = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int modelStatus = 200;
    private volatile java.util.concurrent.CountDownLatch modelGate;
    private volatile java.util.concurrent.CountDownLatch modelArrivals;
    private HttpServer server;
    private WebClient testWeb;
    private ToolRegistry registry;
    private ChatGptResponsesClient client;
    private AiAssistantSetting setting;
    private ChatGptCredentials credentials;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] output;
            if (exchange.getRequestURI().getPath().equals("/models")) {
                modelHits.incrementAndGet();
                if (modelArrivals != null) modelArrivals.countDown();
                if (modelGate != null) {
                    try { modelGate.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                output = "{\"models\":[{\"slug\":\"subscription-model\",\"display_name\":\"Test Model\",\"visibility\":\"list\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                paths.add(exchange.getRequestURI().getPath());
                requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                output = streams.remove().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/models") ? modelStatus : 200, 0);
            // Deliberately split UTF-8 Chinese characters across HTTP chunks.
            for (byte b : output) { exchange.getResponseBody().write(b); exchange.getResponseBody().flush(); }
            exchange.close();
        });
        server.start();
        registry = new ToolRegistry();
        credentials = mock(ChatGptCredentials.class);
        when(credentials.accessToken()).thenReturn("test-oauth-token");
        WebClient web = WebClient.builder().clientConnector(new JdkClientHttpConnector())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        testWeb = web;
        client = new ChatGptResponsesClient(credentials, registry, web);
        setting = new AiAssistantSetting();
        setting.setProvider("chatgpt");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void modelsCacheIsSingleFlightAndDoesNotExposeMutableCache() {
        var copies = reactor.core.publisher.Flux.range(0, 8)
                .flatMap(i -> client.models()).collectList().block(Duration.ofSeconds(10));
        assertEquals(1, modelHits.get());
        copies.getFirst().removeAll();
        assertEquals(1, client.models().block().size());
        assertEquals(1, modelHits.get());
        client.invalidateModels();
        assertEquals(1, client.models().block().size());
        assertEquals(2, modelHits.get());
        when(credentials.accessToken()).thenReturn("new-account-token");
        client.models().block();
        assertEquals(3, modelHits.get());
        when(credentials.accessToken()).thenThrow(new IllegalStateException("disconnected"));
        assertThrows(IllegalStateException.class, () -> client.models().block());
        assertEquals(3, modelHits.get());
    }

    @Test
    void failedModelsFetchIsNotCachedAndSuccessfulCacheExpires() throws Exception {
        modelStatus = 503;
        assertThrows(RuntimeException.class, () -> client.models().block(Duration.ofSeconds(10)));
        modelStatus = 200;
        client.models().block(Duration.ofSeconds(10));
        assertEquals(2, modelHits.get());
        var shortCache = new ChatGptResponsesClient(credentials, registry, testWeb, Duration.ofMillis(100));
        shortCache.models().block(Duration.ofSeconds(10));
        int before = modelHits.get();
        Thread.sleep(250);
        shortCache.prewarmModels().block(Duration.ofSeconds(10));
        assertEquals(before + 1, modelHits.get());
    }

    @Test
    void warmupDoesNotGenerateAndRefreshAheadKeepsChatOffSlowMetadataRequest() throws Exception {
        var shortCache = new ChatGptResponsesClient(credentials, registry, testWeb, Duration.ofMillis(400));
        shortCache.prewarmModels().block(Duration.ofSeconds(10));
        assertEquals(1, modelHits.get());
        assertTrue(requests.isEmpty()); // no /responses call for warmup
        Thread.sleep(270); // refresh-ahead threshold (60%) reached, freshness TTL not yet reached
        modelGate = new java.util.concurrent.CountDownLatch(1);
        modelArrivals = new java.util.concurrent.CountDownLatch(1);
        var refresh = shortCache.prewarmModels().toFuture();
        assertTrue(modelArrivals.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(refresh.isDone());
        for (int i = 0; i < 5; i++) assertEquals(1, shortCache.models().block(Duration.ofMillis(500)).size());
        assertEquals(2, modelHits.get());
        modelGate.countDown();
        assertEquals(1, refresh.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
        assertTrue(requests.isEmpty());
    }

    @Test
    void staleListRefreshesInBackgroundButCannotSurviveHardExpiryOrAuthorizationLoss() throws Exception {
        var shortCache = new ChatGptResponsesClient(credentials, registry, testWeb, Duration.ofMillis(150));
        shortCache.prewarmModels().block(Duration.ofSeconds(10));
        Thread.sleep(200);
        modelGate = new java.util.concurrent.CountDownLatch(1);
        modelArrivals = new java.util.concurrent.CountDownLatch(1);
        assertEquals(1, shortCache.models().block(Duration.ofMillis(500)).size());
        assertTrue(modelArrivals.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, modelHits.get());
        modelStatus = 503;
        modelGate.countDown();
        assertThrows(RuntimeException.class, () -> shortCache.prewarmModels().block(Duration.ofSeconds(5)));
        Thread.sleep(1000); // past 6 x freshness TTL since the last successful response
        assertThrows(RuntimeException.class, () -> shortCache.models().block(Duration.ofSeconds(5)));
        modelStatus = 200;
        assertEquals(1, shortCache.prewarmModels().block(Duration.ofSeconds(5)).size());
        when(credentials.accessToken()).thenThrow(new IllegalStateException("disconnected"));
        assertThrows(IllegalStateException.class, () -> shortCache.models().block());
    }

    @Test
    void lateWarmupCannotRepopulateInvalidatedCacheAndGenerationRequestIsUnchanged() throws Exception {
        modelGate = new java.util.concurrent.CountDownLatch(1);
        modelArrivals = new java.util.concurrent.CountDownLatch(1);
        var old = client.prewarmModels().toFuture();
        assertTrue(modelArrivals.await(2, java.util.concurrent.TimeUnit.SECONDS));
        client.invalidateModels();
        modelGate.countDown();
        old.get(5, java.util.concurrent.TimeUnit.SECONDS);
        client.models().block(Duration.ofSeconds(5));
        assertEquals(2, modelHits.get()); // discarded result cannot satisfy the new cache
        assertTrue(requests.isEmpty());
        client.prewarmTransport().block(Duration.ofSeconds(5));
        setting.setChatGptModel("subscription-model");
        var messages = List.of(new ChatMessage("system", "quality rules"), new ChatMessage("user", "question"));
        streams.add(text("answer") + completed(mapper.createArrayNode()));
        client.chat(messages, setting, Set.of(), "admin").collectList().block(Duration.ofSeconds(5));
        JsonNode warmedRequest = requests.getLast();
        client.invalidateModels();
        streams.add(text("answer") + completed(mapper.createArrayNode()));
        client.chat(messages, setting, Set.of(), "admin").collectList().block(Duration.ofSeconds(5));
        assertEquals(warmedRequest, requests.getLast());
        assertEquals("subscription-model", warmedRequest.path("model").asText());
    }

    @Test
    void transportWarmupAlwaysUsesRealGetEvenWithFreshMetadataAndNeverGenerates() throws Exception {
        client.prewarmModels().block(Duration.ofSeconds(5));
        assertEquals(1,modelHits.get());
        modelGate=new java.util.concurrent.CountDownLatch(1);
        modelArrivals=new java.util.concurrent.CountDownLatch(1);
        var pending=client.prewarmTransport().toFuture();
        assertTrue(modelArrivals.await(2,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2,modelHits.get());
        var duplicate=client.prewarmTransport().toFuture();
        assertEquals(1,client.models().block(Duration.ofMillis(500)).size());
        modelGate.countDown();pending.get(5,java.util.concurrent.TimeUnit.SECONDS);
        duplicate.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2,modelHits.get());
        assertTrue(requests.isEmpty());
        assertEquals(1,client.models().block().size());
    }

    @Test
    void chatPersonaHasNoManagementInstructionsOrDraftTools() {
        mockTool("searchSiteArticles");
        mockTool("createArticle");
        streams.add(text("你好") + completed(mapper.createArrayNode()));
        assertEquals("你好", join(client.chat(List.of(new ChatMessage("user", "你好")),
                setting, Set.of("searchSiteArticles"), "admin")));
        JsonNode request = requests.getFirst();
        assertTrue(request.path("instructions").asText().contains("仅进行聊天问答"));
        assertFalse(request.path("instructions").asText().contains("待确认草稿"));
        assertEquals(1, request.path("tools").path(0).path("tools").size());
        assertEquals("searchSiteArticles", request.path("tools").path(0).path("tools").path(0).path("name").asText());
    }

    @Test
    void backgroundGenerationUsesSubscriptionWithoutToolsOrApiKey() {
        streams.add(text("{\"title\":\"订阅文章\"}") + completed(mapper.createArrayNode()));
        AgentService agent = new AgentService(registry, client);
        assertEquals("{\"title\":\"订阅文章\"}",
                agent.generateText(setting, "编辑规则", "生成文章", 3000).block(Duration.ofSeconds(10)));
        JsonNode request = requests.getFirst();
        assertFalse(request.has("tools"));
        assertFalse(request.has("max_output_tokens"));
        assertFalse(request.has("max_tokens"));
        assertTrue(request.path("instructions").asText().contains("编辑规则"));
        assertFalse(request.path("instructions").asText().contains("不支持"));
        assertEquals("subscription-model", request.path("model").asText());
    }

    @Test
    void backgroundGenerationRejectsUnexpectedToolsBeforeExecutingAnything() {
        Tool tool = mockTool("createArticle");
        streams.add(text("partial content") + completed(mapper.createArrayNode().add(call("createArticle", "{}"))));
        assertThrows(IllegalStateException.class,
                () -> client.generateText(setting, "editor", "write article", 3000).block(Duration.ofSeconds(10)));
        verify(tool, never()).execute(any());
    }

    @Test
    void backgroundGenerationPropagatesQuotaAndIncompleteErrors() {
        streams.add("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n");
        var error = assertThrows(IllegalStateException.class,
                () -> client.generateText(setting, "编辑", "写文章", 3000).block(Duration.ofSeconds(10)));
        assertTrue(error.getMessage().contains("额度"));
        streams.add(text("半篇文章"));
        assertThrows(IllegalStateException.class,
                () -> client.generateText(setting, "编辑", "写文章", 3000).block(Duration.ofSeconds(10)));
    }

    @Test
    void subscriptionRoutingNeedsNoApiKeyAndPreservesUtf8() throws Exception {
        streams.add(text("你好，已连接。") + completed(mapper.createArrayNode()));
        AgentService agent = new AgentService(registry, client);
        agent.updateSetting(setting);
        String reply = join(agent.chat(List.of(new ChatMessage("system", "角色规则"),
                new ChatMessage("user", "你好")), "admin"));
        assertEquals("你好，已连接。", reply);
        JsonNode request = requests.getFirst();
        assertEquals("subscription-model", request.path("model").asText());
        assertFalse(request.path("store").asBoolean());
        assertTrue(request.path("stream").asBoolean());
        assertTrue(request.path("instructions").asText().contains("角色规则"));
        assertEquals("user", request.path("input").path(0).path("role").asText());
        for (String unsupported : List.of("max_output_tokens", "max_tokens", "temperature", "previous_response_id", "messages")) {
            assertFalse(request.has(unsupported));
        }
    }

    @Test
    void readsRealArticleToolAndReplaysReasoningAndCallOutput() throws Exception {
        ReactiveExtensionClient storage = mock(ReactiveExtensionClient.class);
        PostContentService content = mock(PostContentService.class);
        Post post = new Post();
        Metadata metadata = new Metadata(); metadata.setName("post-one"); post.setMetadata(metadata);
        Post.PostSpec spec = new Post.PostSpec(); spec.setTitle("测试文章"); post.setSpec(spec);
        when(storage.get(Post.class, "post-one")).thenReturn(Mono.just(post));
        when(content.getHeadContent("post-one")).thenReturn(Mono.empty());
        registry.register(new ArticleTool.GetArticleTool(storage, content));
        ArrayNode output = mapper.createArrayNode();
        output.addObject().put("type", "reasoning").put("id", "rs_1")
                .put("encrypted_content", "opaque-reasoning").putArray("summary");
        output.add(call("getArticle", "{\"id\":\"post-one\"}"));
        streams.add(completed(output));
        streams.add(text("读到了测试文章") + completed(mapper.createArrayNode()));
        assertEquals("读到了测试文章", chat(null));
        JsonNode continuation = requests.get(1).path("input");
        assertEquals("opaque-reasoning", continuation.path(1).path("encrypted_content").asText());
        assertEquals("call_one", continuation.path(3).path("call_id").asText());
        assertTrue(continuation.path(3).path("output").asText().contains("测试文章"));
        verify(storage, never()).create(any());
    }

    @Test
    void createsOnlyPendingDraftWithServerOwnedRequester() throws Exception {
        ReactiveExtensionClient storage = mock(ReactiveExtensionClient.class);
        PendingActionService pending = new PendingActionService();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(PendingActionService.class)).thenReturn(pending);
        new SpringContextBridge().setApplicationContext(context);
        ArticleTool.CreateArticleTool createTool = new ArticleTool.CreateArticleTool(storage);
        registry.register(createTool);
        when(context.getBean(ArticleTool.CreateArticleTool.class)).thenReturn(createTool);
        streams.add(completed(mapper.createArrayNode().add(call("createArticle", """
                {"title":"订阅草稿","content":"# 正文","status":"published","owner":"intruder",
                 "tags":"unrequested","_requester":"intruder","skipConfirmation":true}
                """))));
        String reply = chat(null);
        var actions = pending.listPending("admin");
        assertEquals(1, actions.size());
        var action = actions.getFirst();
        assertTrue(reply.contains(action.getId()));
        assertEquals("draft", action.getPayload().path("status").asText());
        assertEquals("admin", action.getRequester());
        assertFalse(action.getPayload().has("owner"));
        assertFalse(action.getPayload().has("tags"));
        assertFalse(action.getPayload().has("skipConfirmation"));
        verifyNoInteractions(storage);
        assertTrue(pending.cancel(action.getId(), "admin"));
        assertNull(pending.confirmAndExecute(action.getId(), "admin"));
        verifyNoInteractions(storage);
        assertEquals("namespace", requests.getFirst().path("tools").path(0).path("type").asText());
        assertFalse(requests.getFirst().toString().contains("skipConfirmation"));
        // A second proposal confirms through the real internal executor, once, as an unpublished Post.
        ListResult<Post> existing = mock(ListResult.class);
        Post oldPost = new Post();
        Post.PostSpec oldSpec = new Post.PostSpec(); oldSpec.setOwner("admin"); oldPost.setSpec(oldSpec);
        when(existing.getItems()).thenReturn(List.of(oldPost));
        when(storage.list(Post.class, null, null, 0, 1)).thenReturn(Mono.just(existing));
        var storedPost = new java.util.concurrent.atomic.AtomicReference<Post>();
        when(storage.create(any(Post.class))).thenAnswer(inv -> {
            Post post = inv.getArgument(0); storedPost.set(post); return Mono.just(post);
        });
        when(storage.fetch(eq(Post.class), anyString())).thenAnswer(inv -> Mono.just(storedPost.get()));
        when(storage.create(any(Snapshot.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(storage.update(any(Post.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        streams.add(completed(mapper.createArrayNode().add(call("createArticle", "{\"title\":\"第二份草稿\",\"content\":\"# 正文\"}"))));
        chat(null);
        String id = pending.listPending("admin").getFirst().getId();
        assertNull(pending.confirmAndExecute(id, "other-admin"));
        verify(storage, never()).create(any());
        assertTrue(pending.confirmAndExecute(id, "admin").path("success").asBoolean());
        assertNull(pending.confirmAndExecute(id, "admin"));
        var created = org.mockito.ArgumentCaptor.forClass(Post.class);
        verify(storage, times(1)).create(created.capture());
        verify(storage, times(1)).create(any(Snapshot.class));
        assertEquals(false, created.getValue().getSpec().getPublish());
        assertNull(created.getValue().getSpec().getReleaseSnapshot());
        new SpringContextBridge().setApplicationContext(null);
    }

    @Test
    void worksWithoutJacksonInSharedHttpCodecs() throws Exception {
        WebClient textOnly = WebClient.builder().clientConnector(new JdkClientHttpConnector())
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .codecs(c -> {
                    c.registerDefaults(false);
                    c.customCodecs().register(new org.springframework.http.codec.DecoderHttpMessageReader<>(
                            org.springframework.core.codec.StringDecoder.allMimeTypes()));
                    c.customCodecs().register(new org.springframework.http.codec.EncoderHttpMessageWriter<>(
                            org.springframework.core.codec.CharSequenceEncoder.allMimeTypes()));
                    c.customCodecs().register(new org.springframework.http.codec.ServerSentEventHttpMessageReader());
                }).build();
        client = new ChatGptResponsesClient(credentials, registry, textOnly);
        streams.add(text("已连接") + completed(mapper.createArrayNode()));
        assertEquals("已连接", chat(null));
        assertEquals("subscription-model", requests.getFirst().path("model").asText());
    }

    @Test
    void subscriptionTerminalCanOmitAlreadyStreamedToolOutput() throws Exception {
        Tool tool = mockTool("getArticle");
        when(tool.execute(any())).thenReturn("北斗松果731");
        ObjectNode done = mapper.createObjectNode().put("type", "response.output_item.done")
                .put("output_index", 0).set("item", call("getArticle", "{\"id\":\"source\"}"));
        streams.add(event(done.toString()) + completed(mapper.createArrayNode()));
        streams.add(text("北斗松果731") + completed(mapper.createArrayNode()));
        assertEquals("北斗松果731", chat(null));
        verify(tool, times(1)).execute(any());
        assertEquals("function_call", requests.get(1).path("input").path(1).path("type").asText());
    }

    @Test
    void incompleteOrFailedStreamsNeverExecuteTools() throws Exception {
        Tool tool = mockTool("createArticle");
        for (String terminal : List.of("", event("{\"type\":\"response.incomplete\"}"),
                event("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}"))) {
            streams.add(event(mapper.createObjectNode().put("type", "response.output_item.done")
                    .put("output_index", 0)
                    .set("item", call("createArticle", "{\"title\":\"x\",\"content\":\"x\"}")).toString()) + terminal);
            assertTrue(chat(null).contains("[ChatGPT 订阅错误]"));
        }
        verify(tool, never()).execute(any());
    }

    @Test
    void rejectsUnauthorizedToolsAndPersonaEscapes() throws Exception {
        Tool create = mockTool("createArticle");
        streams.add(completed(mapper.createArrayNode().add(call("createArticle", "{}"))));
        assertTrue(chat(Set.of("searchSiteArticles")).contains("未授权"));
        verify(create, never()).execute(any());
        Tool delete = mockTool("deleteArticle");
        streams.add(completed(mapper.createArrayNode().add(call("deleteArticle", "{}"))));
        assertTrue(chat(null).contains("未授权"));
        verify(delete, never()).execute(any());
    }

    @Test
    void rejectsForeignNamespaceAndDuplicateCallIdsBeforeAnyExecution() throws Exception {
        Tool tool = mockTool("getArticle");
        ObjectNode foreign = call("getArticle", "{}").put("namespace", "other");
        streams.add(completed(mapper.createArrayNode().add(foreign)));
        assertTrue(chat(null).contains("未授权"));
        streams.add(completed(mapper.createArrayNode().add(call("getArticle", "{}"))
                .add(call("getArticle", "{}"))));
        assertTrue(chat(null).contains("无效工具"));
        verify(tool, never()).execute(any());
    }

    @Test
    void invalidModelDoesNotStartInference() {
        setting.setChatGptModel("not-in-catalog");
        assertTrue(chat(null).contains("不在当前账户列表"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void apiModeStillRequiresApiKey() {
        AgentService agent = new AgentService(registry, client);
        setting.setProvider("api");
        agent.updateSetting(setting);
        assertTrue(join(agent.chat(List.of(new ChatMessage("user", "hello")))).contains("填写 API Key"));
        verifyNoInteractions(credentials);
    }

    @Test
    void existingApiKeyModeStillUsesChatCompletions() {
        setting.setProvider("api");
        setting.setApiEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        setting.setApiKey("test-api-key");
        streams.add("data: {\"choices\":[{\"delta\":{\"content\":\"API works\"}}]}\n\ndata: [DONE]\n\n");
        AgentService agent = new AgentService(registry, client);
        agent.updateSetting(setting);
        assertEquals("API works", join(agent.chat(List.of(new ChatMessage("user", "hello")))));
        assertEquals("/v1/chat/completions", paths.getFirst());
        assertTrue(requests.getFirst().path("messages").isArray());
        verifyNoInteractions(credentials);
    }

    private Tool mockTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn("test tool");
        when(tool.getParametersJsonSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        registry.register(tool);
        return tool;
    }

    private String chat(Set<String> tools) {
        return join(client.chat(List.of(new ChatMessage("user", "测试")), setting, tools, "admin"));
    }

    private String join(reactor.core.publisher.Flux<String> stream) {
        return String.join("", stream.collectList().block(Duration.ofSeconds(15)));
    }

    private ObjectNode call(String name, String arguments) {
        return mapper.createObjectNode().put("type", "function_call").put("id", "fc_1")
                .put("call_id", "call_one").put("namespace", "blog").put("name", name).put("arguments", arguments);
    }

    private String completed(ArrayNode output) {
        ObjectNode result = mapper.createObjectNode().put("type", "response.completed");
        result.putObject("response").put("status", "completed").set("output", output);
        return event(result.toString());
    }

    private String text(String value) {
        return event(mapper.createObjectNode().put("type", "response.output_text.delta").put("delta", value).toString());
    }

    private String event(String data) { return "data: " + data + "\r\n\r\n"; }
}
