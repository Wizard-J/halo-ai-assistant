package io.codex.haloaiassistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.autoops.AutoOpsService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.plugin.SettingFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatEndpoint {

    private final SettingFetcher settingFetcher;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;
    private final AutoOpsService autoOpsService;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                .POST("/api/ai-assistant/chat", this::handleChat)
                .GET("/api/ai-assistant/tools", this::handleGetTools)
                .GET("/api/ai-assistant/chat-page", this::handleChatPage)
                .GET("/api/ai-assistant/auto-ops/test", this::handleAutoOpsTest)
                .GET("/api/ai-assistant/auto-ops/debug", this::handleDebug)
                .GET("/api/ai-assistant/", this::handleChatPageRedirect)
                .GET("/api/ai-assistant/daily-push", this::handleDailyPush)
                .build();
    }

    // ========== AI 对话 ==========

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    loadAiSetting();
                    String message = body.has("message") ? body.get("message").asText() : "";
                    if (message.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "消息不能为空"));
                    }
                    List<AgentService.ChatMessage> history = new ArrayList<>();
                    if (body.has("history") && body.get("history").isArray()) {
                        for (JsonNode msg : body.get("history")) {
                            history.add(new AgentService.ChatMessage(
                                    msg.get("role").asText(), msg.get("content").asText()));
                        }
                    }
                    history.add(new AgentService.ChatMessage("user", message));
                    return ServerResponse.ok()
                            .header("Content-Type", "text/event-stream; charset=utf-8")
                            .header("Cache-Control", "no-cache")
                            .body(BodyInserters.fromProducer(
                                    agentService.chat(history), String.class));
                });
    }

    private Mono<ServerResponse> handleGetTools(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("tools", toolRegistry.getAll().stream()
                .map(t -> Map.of("name", t.getName(), "description", t.getDescription()))
                .toList()));
    }

    // ========== 聊天页面 ==========

    private Mono<ServerResponse> handleChatPage(ServerRequest request) {
        return Mono.fromCallable(() -> {
                    String title = "AI 智能助手";
                    String icon = "\uD83E\uDDE0";
                    String greeting = "你好，我是 Halo AI 智能助手";
                    java.util.Optional<AiAssistantSetting> opt = settingFetcher.fetch("basic", AiAssistantSetting.class);
                    if (opt.isPresent()) {
                        AiAssistantSetting s = opt.get();
                        if (s.getPageTitle() != null && !s.getPageTitle().isBlank()) title = s.getPageTitle();
                        if (s.getPageIcon() != null && !s.getPageIcon().isBlank()) icon = s.getPageIcon();
                        if (s.getGreeting() != null && !s.getGreeting().isBlank()) greeting = s.getGreeting();
                    }
                    String html = CHAT_PAGE_HTML
                            .replace("<title>AI 智能助手</title>",
                                    "<title>" + htmlEsc(title) + "</title>")
                            .replace("<span class=\"brand-title\">AI 智能助手</span>",
                                    "<span class=\"brand-title\">" + htmlEsc(title) + "</span>")
                            .replace("<span class=\"brand-mark\" aria-hidden=\"true\"><i class=\"ri-sparkling-2-fill\"></i></span>",
                                    "<span class=\"brand-mark\" aria-hidden=\"true\">" + icon + "</span>")
                            .replace("<h1>你好，我是 Halo AI 智能助手</h1>",
                                    "<h1>" + htmlEsc(greeting) + "</h1>");
                    return html;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(html -> ServerResponse.ok()
                        .header("Content-Type", "text/html; charset=utf-8")
                        .bodyValue(html))
                .onErrorResume(error -> {
                    log.error("渲染聊天页面失败", error);
                    return ServerResponse.ok()
                            .header("Content-Type", "text/html; charset=utf-8")
                            .bodyValue("<html><body><h1>页面加载失败</h1><p>"
                                    + htmlEsc(error.getMessage()) + "</p></body></html>");
                });
    }

    private static String htmlEsc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Mono<ServerResponse> handleChatPageRedirect(ServerRequest request) {
        return ServerResponse.temporaryRedirect(
                java.net.URI.create("/api/ai-assistant/chat-page"))
                .build();
    }

    // ========== 自动运维测试 ==========

    private Mono<ServerResponse> handleAutoOpsTest(ServerRequest request) {
        log.info("[AUTO-OPS-TEST] === 端点被调用 ===");
        return Mono.defer(() -> {
                    log.info("[AUTO-OPS-TEST] enter defer lambda, calling testNow()");
                    return autoOpsService.testNow()
                            .doOnNext(r -> log.info("[AUTO-OPS-TEST] testNow() 返回: {}", r))
                            .flatMap(result -> {
                                log.info("[AUTO-OPS-TEST] 构建成功响应");
                                return ServerResponse.ok()
                                        .header("Content-Type", "application/json; charset=utf-8")
                                        .bodyValue(result);
                            });
                })
                .doOnError(e -> log.error("[AUTO-OPS-TEST] Mono error before onErrorResume: {}", e.toString()))
                .onErrorResume(error -> {
                    log.error("[AUTO-OPS-TEST] 自动运维测试失败", error);
                    String errMsg = error.getMessage();
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    if (errMsg == null && cause != null) errMsg = cause.getClass().getSimpleName();
                    if (errMsg == null) errMsg = "未知错误";
                    return ServerResponse.ok()
                            .header("Content-Type", "application/json; charset=utf-8")
                            .bodyValue(Map.of("success", false, "error", errMsg));
                });
    }

    private Mono<ServerResponse> handleDebug(ServerRequest request) {
        return ServerResponse.ok()
                .header("Content-Type", "application/json; charset=utf-8")
                .bodyValue(Map.of("status", "ok", "message", "route registered",
                        "timestamp", Instant.now().toString()));
    }

    // ========== 每日推送 ==========

    private Mono<ServerResponse> handleDailyPush(ServerRequest request) {
        AiAssistantSetting setting = loadPushSetting();

        String secret = request.queryParam("secret").orElse("");
        if (!setting.getPushSecret().isBlank() && !secret.equals(setting.getPushSecret())) {
            return ServerResponse.status(403).bodyValue(Map.of("error", "推送密钥错误"));
        }

        String timeRange = request.queryParam("time").orElse(setting.getPushTime());

        return fetchPostsForPush(timeRange)
                .flatMap(posts -> {
                    if (posts.isEmpty()) {
                        return sendAndRespond("📝 博客更新 0 篇",
                                "今天还没有新文章发布哦～", setting);
                    }
                    String pushContent = buildPushContent(posts, timeRange);
                    String pushTitle = "📝 博客更新 " + posts.size() + " 篇";
                    return sendAndRespond(pushTitle, pushContent, setting);
                });
    }

    private Mono<ServerResponse> sendAndRespond(String title, String content, AiAssistantSetting setting) {
        return sendPush(title, content, setting.getPushChannel(), setting.getPushToken())
                .flatMap(success -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", success);
                    result.put("title", title);
                    result.put("message", success ? "✅ 推送成功" : "❌ 推送失败");
                    return ServerResponse.ok()
                            .header("Content-Type", "application/json; charset=utf-8")
                            .bodyValue(result);
                });
    }

    private Mono<List<Post>> fetchPostsForPush(String timeRange) {
        return client.list(Post.class, null, null)
                .filter(post -> post.getSpec() != null && post.getSpec().getPublishTime() != null)
                .collectList()
                .flatMap(posts -> {
                    ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
                    var filtered = posts.stream()
                            .filter(post -> {
                                ZonedDateTime pt = post.getSpec().getPublishTime()
                                        .atZone(ZoneId.of("Asia/Shanghai"));
                                boolean today = pt.toLocalDate().equals(now.toLocalDate());
                                boolean yesterday = pt.toLocalDate().equals(now.minusDays(1).toLocalDate());
                                return switch (timeRange) {
                                    case "today" -> today;
                                    case "yesterday" -> yesterday;
                                    default -> today || yesterday;
                                };
                            })
                            .sorted((a, b) -> a.getSpec().getPublishTime()
                                    .compareTo(b.getSpec().getPublishTime()))
                            .toList();
                    return Mono.just(filtered);
                });
    }

    private String buildPushContent(List<Post> posts, String timeRange) {
        var sb = new StringBuilder("# 📝 博客更新\n\n");
        if ("yesterday".equals(timeRange)) sb.append("> 昨日更新 ").append(posts.size()).append(" 篇\n\n");
        else if ("today".equals(timeRange)) sb.append("> 今日更新 ").append(posts.size()).append(" 篇\n\n");
        else sb.append("> 共 ").append(posts.size()).append(" 篇更新\n\n");
        for (Post post : posts) {
            if (post.getSpec() == null || post.getSpec().getTitle() == null) continue;
            String title = post.getSpec().getTitle();
            String url = post.getStatusOrDefault().getPermalink();
            if (url != null && !url.isBlank()) sb.append("- [").append(escapeMd(title)).append("](").append(url).append(")\n");
            else sb.append("- ").append(escapeMd(title)).append("\n");
        }
        sb.append("---\n> 📊 共 ").append(posts.size()).append(" 篇 · ")
                .append(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        return sb.toString();
    }

    private String escapeMd(String text) {
        return text.replace("|", "\\|").replace("[", "\\[").replace("]", "\\]");
    }

    private Mono<Boolean> sendPush(String title, String content, String channel, String token) {
        return Mono.fromCallable(() -> {
            try {
                return "pushplus".equals(channel) ? sendPushPlus(title, content, token)
                        : sendServerChan(title, content, token);
            } catch (Exception e) {
                log.error("推送异常", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean sendServerChan(String title, String content, String sendKey) {
        try {
            String url = "https://sctapi.ftqq.com/" + sendKey + ".send";
            String body = "title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                    + "&desp=" + URLEncoder.encode(content, StandardCharsets.UTF_8)
                    + "&tags=" + URLEncoder.encode("博客推送", StandardCharsets.UTF_8);
            var conn = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (var os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            log.info("Server酱 推送: {}", code);
            return code == 200;
        } catch (Exception e) {
            log.error("Server酱 推送异常", e);
            return false;
        }
    }

    private boolean sendPushPlus(String title, String content, String token) {
        try {
            String url = "https://www.pushplus.plus/send";
            String payload = objectMapper.createObjectNode()
                    .put("token", token).put("title", title)
                    .put("content", content).put("template", "markdown").toString();
            var conn = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (var os = conn.getOutputStream()) { os.write(payload.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            log.info("PushPlus 推送: {}", code);
            return code == 200;
        } catch (Exception e) {
            log.error("PushPlus 推送异常", e);
            return false;
        }
    }

    private void loadAiSetting() {
        settingFetcher.fetch("basic", AiAssistantSetting.class)
                .ifPresent(agentService::updateSetting);
    }

    private AiAssistantSetting loadPushSetting() {
        AiAssistantSetting setting = new AiAssistantSetting();
        settingFetcher.fetch("basic", AiAssistantSetting.class).ifPresent(s -> {
            setting.setApiEndpoint(s.getApiEndpoint());
            setting.setApiKey(s.getApiKey());
            setting.setModel(s.getModel());
            setting.setMaxTokens(s.getMaxTokens());
            setting.setSystemPrompt(s.getSystemPrompt());
        });
        settingFetcher.fetch("push", AiAssistantSetting.class).ifPresent(s -> {
            setting.setPushSecret(s.getPushSecret());
            setting.setPushChannel(s.getPushChannel());
            setting.setPushToken(s.getPushToken());
            setting.setPushTime(s.getPushTime());
        });
        agentService.updateSetting(setting);
        return setting;
    }

    private static final String CHAT_PAGE_HTML = loadChatPage();

    private static String loadChatPage() {
        try (InputStream is = AiChatEndpoint.class.getClassLoader()
                .getResourceAsStream("chat-page.html")) {
            if (is == null) return "<html><body><h1>Chat page not found</h1></body></html>";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load chat-page.html", e);
        }
    }
}
