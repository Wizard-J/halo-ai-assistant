package io.codex.haloaiassistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.autoops.AutoOpsService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import io.codex.haloaiassistant.persona.Conversation;
import io.codex.haloaiassistant.persona.PersonaDefinition;
import io.codex.haloaiassistant.persona.PersonaService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.SettingFetcher;
import run.halo.app.plugin.ReactiveSettingFetcher;


@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatEndpoint {

    private final SettingFetcher settingFetcher;
    private final ReactiveSettingFetcher reactiveSettingFetcher;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;
    private final AutoOpsService autoOpsService;
    private final ReactiveExtensionClient client;
    private final PersonaService personaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                .POST("/api/ai-assistant/chat", this::handleChat)
                .GET("/api/ai-assistant/tools", this::handleGetTools)
                .GET("/api/ai-assistant/chat-page", this::handleChatPage)
                .GET("/api/ai-assistant/auto-ops/test", this::handleAutoOpsTest)
                .GET("/api/ai-assistant/", this::handleChatPageRedirect)
                .GET("/api/ai-assistant/daily-push", this::handleDailyPush)
                .build();
    }

    // ========== AI 对话（Persona 感知） ==========

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        // 先异步加载 AI 配置，再处理请求
        return loadAiSetting()
                .then(request.bodyToMono(JsonNode.class))
                .flatMap(body -> {
                    String message = body.has("message") ? body.get("message").asText() : "";
                    if (message == null || message.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "消息不能为空"));
                    }

                    // 读取 Persona 和 Session 参数
                    String personaId = body.has("persona") ? body.get("persona").asText("default") : "default";
                    String sessionId = body.has("sessionId") ? body.get("sessionId").asText() : "";
                    String overrideSystemPrompt = body.has("systemPrompt")
                            ? body.get("systemPrompt").asText("") : "";

                    // 构建历史消息列表
                    List<AgentService.ChatMessage> history = new ArrayList<>();
                    if (body.has("history") && body.get("history").isArray()) {
                        for (JsonNode msg : body.get("history")) {
                            history.add(new AgentService.ChatMessage(
                                    msg.get("role").asText(),
                                    msg.get("content").asText()));
                        }
                    }
                    history.add(new AgentService.ChatMessage("user", message));

                    // 如果指定了 persona，加载其 system prompt 并管理上下文
                    if (sessionId != null && !sessionId.isBlank()) {
                        return chatWithPersona(sessionId, personaId, overrideSystemPrompt,
                                history, body);
                    }

                    // 向后兼容：无 persona 参数时使用默认行为
                    return ServerResponse.ok()
                            .header("Content-Type", "text/event-stream; charset=utf-8")
                            .header("Cache-Control", "no-cache")
                            .body(BodyInserters.fromProducer(
                                    agentService.chat(history), String.class));
                })
                .onErrorResume(e -> {
                    log.error("Chat API 内部错误", e);
                    return ServerResponse.status(500)
                            .bodyValue(java.util.Map.of("error", "服务器内部错误: " + e.getMessage()));
                });
    }

    /**
     * 带 Persona 上下文的对话处理
     */
    private Mono<ServerResponse> chatWithPersona(String sessionId, String personaId,
                                                  String overrideSystemPrompt,
                                                  List<AgentService.ChatMessage> history,
                                                  JsonNode body) {
        // 加载 Persona 定义，使用硬编码兜底
        Mono<PersonaDefinition> personaMono = personaService.getPersona(personaId)
                .onErrorResume(e -> {
                    log.warn("未找到 Persona {}，使用默认角色", personaId);
                    // 尝试加载 default，如果也没有则返回 null
                    return personaService.getPersona("default")
                            .onErrorResume(e2 -> Mono.empty());
                })
                .switchIfEmpty(Mono.fromCallable(() -> {
                    // 硬编码兜底 Persona
                    PersonaDefinition fallback = new PersonaDefinition();
                    PersonaDefinition.PersonaSpec spec = new PersonaDefinition.PersonaSpec();
                    spec.setSystemPrompt("你是一个智能博客助手，可以帮助用户管理博客。\n"
                            + "你可以执行以下操作：\n"
                            + "- 查看文章列表、创建文章、更新文章、删除文章\n"
                            + "- 管理分类和标签\n"
                            + "- 查看和管理评论\n"
                            + "请用友好的语气与用户交流。");
                    fallback.setSpec(spec);
                    return fallback;
                }));

        return personaMono.flatMap(persona -> {
            // 确定 system prompt
            String systemPrompt;
            if (!overrideSystemPrompt.isBlank()) {
                systemPrompt = overrideSystemPrompt;
            } else if (persona.getSpec() != null && persona.getSpec().getSystemPrompt() != null) {
                systemPrompt = persona.getSpec().getSystemPrompt();
            } else {
                systemPrompt = "你是一个智能博客助手，可以帮助用户管理博客。";
            }

            // 构建带 system prompt 的完整消息列表
            List<AgentService.ChatMessage> fullHistory = new ArrayList<>();
            fullHistory.add(new AgentService.ChatMessage("system", systemPrompt));
            fullHistory.addAll(history);

            // 流式返回（暂时简化，后续迭代加入服务端持久化）
            return ServerResponse.ok()
                    .header("Content-Type", "text/event-stream; charset=utf-8")
                    .header("Cache-Control", "no-cache")
                    .body(BodyInserters.fromProducer(
                            agentService.chat(fullHistory), String.class));
        });
    }

    /**
     * 将对话消息保存到服务端
     */
    private Mono<Void> saveConversationMessages(String sessionId, String personaId,
                                                 List<AgentService.ChatMessage> messages) {
        ArrayNode messagesJson = objectMapper.createArrayNode();
        for (AgentService.ChatMessage msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            messagesJson.add(msgNode);
        }
        return personaService.appendMessages(sessionId, personaId, messagesJson)
                .then();
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
                    try {
                        java.util.Optional<AiAssistantSetting> opt = settingFetcher.fetch("basic", AiAssistantSetting.class);
                        if (opt.isPresent()) {
                            AiAssistantSetting s = opt.get();
                            if (s.getPageTitle() != null && !s.getPageTitle().isBlank()) title = s.getPageTitle();
                            if (s.getPageIcon() != null && !s.getPageIcon().isBlank()) icon = s.getPageIcon();
                            if (s.getGreeting() != null && !s.getGreeting().isBlank()) greeting = s.getGreeting();
                        }
                    } catch (Exception e) {
                        log.warn("读取配置失败，使用默认值", e);
                    }

                    // Persona 列表由前端通过 /api/ai-assistant/personas 异步加载
                    // 不在页面渲染时 block，避免扩展未就绪时出错

                    String html = CHAT_PAGE_HTML
                            .replace("<title>AI 智能助手</title>", "<title>" + escapeHtml(title) + "</title>")
                            .replace(">AI 智能助手<", ">" + escapeHtml(title) + "<")
                            .replace("你好，我是 Halo AI 智能助手",
                                    escapeHtml(greeting));
                    return ServerResponse.ok()
                            .header("Content-Type", "text/html; charset=utf-8")
                            .bodyValue(html);
                })
                .flatMap(r -> r)
                .onErrorResume(e -> {
                    log.error("渲染聊天页面失败", e);
                    return ServerResponse.status(500)
                            .bodyValue(java.util.Map.of("error", "页面渲染失败，请稍后再试"));
                });
    }

    // ========== 原有路由保持不变 ==========    // ========== 原有路由保持不变 ==========

    private Mono<ServerResponse> handleAutoOpsTest(ServerRequest request) {
        return autoOpsService.testNow()
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }


    private Mono<ServerResponse> handleChatPageRedirect(ServerRequest request) {
        return ServerResponse.permanentRedirect(URI.create("/api/ai-assistant/chat-page")).build();
    }

    private Mono<ServerResponse> handleDailyPush(ServerRequest request) {
        String secret = request.queryParam("secret").orElse("");
        String channel = request.queryParam("channel").orElse("");
        return pushDailySummary(secret, channel)
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<Map<String, Object>> pushDailySummary(String secret, String channelOverride) {
        return Mono.fromCallable(() -> {
            AiAssistantSetting pushSetting = loadPushSetting();
            if (pushSetting.getPushSecret() != null && !pushSetting.getPushSecret().isBlank()
                    && !pushSetting.getPushSecret().equals(secret)) {
                return Map.<String, Object>of("success", false, "error", "密钥无效");
            }

            String ch = channelOverride.isBlank() ? pushSetting.getPushChannel() : channelOverride;
            String token = pushSetting.getPushToken();

            if (ch == null || ch.isBlank() || token == null || token.isBlank()) {
                return Map.<String, Object>of("success", false, "error", "推送渠道或 Token 未配置");
            }

            String timeOption = pushSetting.getPushTime() != null ? pushSetting.getPushTime() : "today";

            List<Post> todayPosts = getPostsByTime(timeOption);
            if (todayPosts.isEmpty()) {
                return Map.<String, Object>of("success", true, "message", "没有需要推送的文章");
            }

            String title = "📝 博客更新";
            if ("yesterday".equals(timeOption)) title = "📝 昨日博客更新";
            else if ("both".equals(timeOption)) title = "📝 博客更新汇总";

            String content = buildPushContent(todayPosts, timeOption);
            boolean sent = Boolean.TRUE.equals(sendPush(title, content, ch, token).block());
            return Map.<String, Object>of(
                    "success", sent,
                    "message", sent ? "推送成功" : "推送失败",
                    "count", todayPosts.size(),
                    "channel", ch
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<Post> getPostsByTime(String timeOption) {
        try {
            ZoneId zone = ZoneId.of("Asia/Shanghai");
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime start;
            if ("yesterday".equals(timeOption)) {
                start = now.minusDays(1).toLocalDate().atStartOfDay(zone);
            } else if ("both".equals(timeOption)) {
                start = now.minusDays(1).toLocalDate().atStartOfDay(zone);
            } else {
                start = now.toLocalDate().atStartOfDay(zone);
            }

            ListResult<Post> result = client.list(Post.class, null, null, 0, 100).block();
            if (result == null) return List.of();

            return result.getItems().stream()
                    .filter(post -> post.getSpec() != null
                            && post.getSpec().getPublishTime() != null
                            && post.getSpec().getPublishTime().isAfter(start.toInstant()))
                    .toList();
        } catch (Exception e) {
            log.error("获取文章失败", e);
            return List.of();
        }
    }

    private String buildPushContent(List<Post> posts, String timeOption) {
        StringBuilder sb = new StringBuilder();
        if ("both".equals(timeOption)) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            ZonedDateTime todayStart = now.toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai"));
            List<Post> todayPosts = posts.stream()
                    .filter(p -> p.getSpec().getPublishTime().isAfter(todayStart.toInstant()))
                    .toList();
            List<Post> yesterdayPosts = posts.stream()
                    .filter(p -> !p.getSpec().getPublishTime().isAfter(todayStart.toInstant()))
                    .toList();
            if (!yesterdayPosts.isEmpty()) {
                sb.append("### 📅 昨日（").append(now.minusDays(1).format(DateTimeFormatter.ofPattern("MM月dd日")))
                        .append("）\n\n");
                appendPostList(sb, yesterdayPosts);
            }
            if (!todayPosts.isEmpty()) {
                sb.append("### 📅 今日（").append(now.format(DateTimeFormatter.ofPattern("MM月dd日")))
                        .append("）\n\n");
                appendPostList(sb, todayPosts);
            }
        } else {
            appendPostList(sb, posts);
        }
        return sb.toString();
    }

    private void appendPostList(StringBuilder sb, List<Post> posts) {
        if (posts.isEmpty()) {
            sb.append("暂无更新\n\n");
            return;
        }
        sb.append("> 共 ").append(posts.size()).append(" 篇更新\n\n");
        for (Post post : posts) {
            if (post.getSpec() == null || post.getSpec().getTitle() == null) continue;
            String title = post.getSpec().getTitle();
            String url = post.getStatusOrDefault().getPermalink();
            if (url != null && !url.isBlank()) {
                sb.append("- [").append(escapeMd(title)).append("](").append(url).append(")\n");
            } else {
                sb.append("- ").append(escapeMd(title)).append("\n");
            }
        }
        sb.append("---\n> 📊 共 ").append(posts.size()).append(" 篇 · ")
                .append(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
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

    private Mono<Void> loadAiSetting() {
        return reactiveSettingFetcher.fetch("basic", AiAssistantSetting.class)
                .defaultIfEmpty(new AiAssistantSetting())
                .doOnNext(agentService::updateSetting)
                .then();
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

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeJs(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
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
