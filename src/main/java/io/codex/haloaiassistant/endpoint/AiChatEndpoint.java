package io.codex.haloaiassistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.agent.confirmation.PendingAction;
import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import io.codex.haloaiassistant.agent.confirmation.RiskLevel;
import io.codex.haloaiassistant.autoops.AutoOpsService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import io.codex.haloaiassistant.persona.ConversationRef;
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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
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
public class AiChatEndpoint implements CustomEndpoint {

    public static final GroupVersion API_VERSION = new GroupVersion(
            "console.api.wizardj-ai-assistant.plugin.halo.run", "v1alpha1");
    public static final String PUBLIC_API_BASE = "/apis/" + API_VERSION;

    private final SettingFetcher settingFetcher;
    private final ReactiveSettingFetcher reactiveSettingFetcher;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;
    private final AutoOpsService autoOpsService;
    private final ReactiveExtensionClient client;
    private final PersonaService personaService;
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern CLIENT_EVENT_LINE = Pattern.compile(
            "(?m)^\\s*" + Pattern.quote(AgentService.CLIENT_EVENT_PREFIX) + ".*(?:\\R|$)");

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                .POST("/chat", this::handleChat)
                .GET("/tools", this::handleGetTools)
                .GET("/me", this::handleGetMe)
                .GET("/chat-page", this::handleChatPage)
                .POST("/auto-ops/test", this::handleAutoOpsTest)
                .GET("/", this::handleChatPageRedirect)
                .GET("/config-check", this::handleConfigCheck)
                .POST("/daily-push", this::handleDailyPush)
                .GET("/pending-actions", this::handleListPendingActions)
                .POST("/pending-actions/{id}/confirm", this::handleConfirmAction)
                .POST("/pending-actions/{id}/cancel", this::handleCancelAction)
                .GET("/pending-actions/{id}", this::handleGetPendingAction)
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return API_VERSION;
    }

    // ========== AI 对话（Persona 感知） ==========

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        return request.principal()
                .flatMap(principal -> handleAuthenticatedChat(request, principal.getName()))
                .switchIfEmpty(ServerResponse.status(401).bodyValue(Map.of("error", "请先登录 Halo Console")));
    }

    Mono<ServerResponse> handleAuthenticatedChat(ServerRequest request, String requester) {
        // 先异步加载 AI 配置，再处理请求
        return loadAiSetting()
                .then(request.bodyToMono(String.class))
                .flatMap(this::parseRequestBody)
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
                    if (!sessionId.isBlank() || !isBlogOpsPersona(personaId)) {
                        // Missing client session IDs must never promote a chat persona to blog administrator.
                        String scopedSession = sessionId.isBlank() ? "user-" + requester : sessionId;
                        return chatWithPersona(scopedSession, personaId, overrideSystemPrompt,
                                history, body, requester);
                    }

                    // 向后兼容：无 persona 参数时使用默认行为
                    return ServerResponse.ok()
                            .header("Content-Type", "text/event-stream; charset=utf-8")
                            .header("Cache-Control", "no-cache")
                            .body(BodyInserters.fromProducer(
                                    agentService.chat(history, requester), String.class));
                })
                .onErrorResume(e -> {
                    log.error("Chat API 内部错误", e);
                    return ServerResponse.status(500)
                            .bodyValue(java.util.Map.of("error", "服务器内部错误: " + e.getMessage()));
                });
    }

    private Mono<JsonNode> parseRequestBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Mono.error(new IllegalArgumentException("请求体不能为空"));
        }
        try {
            return Mono.just(objectMapper.readTree(rawBody));
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("请求体 JSON 格式错误"));
        }
    }

    /**
     * 带 Persona 上下文的对话处理
     */
    private Mono<ServerResponse> chatWithPersona(String sessionId, String personaId,
                                                  String overrideSystemPrompt,
                                                  List<AgentService.ChatMessage> history,
                                                  JsonNode body, String requester) {
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

            // 追加 Persona 上下文内容（如 AGENTS.md）
            final String finalSystemPrompt;
            String contextContent = persona.getSpec() != null
                    ? persona.getSpec().getContextContent() : null;
            boolean blogOpsEnabled = isBlogOpsPersona(personaId);
            String personaGuard = blogOpsEnabled
                    ? "\n\n【联网查询边界】\n"
                    + "需要最新、当前或外部公开事实时，可调用 webSearch。每次只查一个具体问题，limit 默认 3；"
                    + "不要连续重复搜索，不要要求抓取网页全文。回答时基于压缩摘要；"
                    + "凡是使用联网结果陈述事实，正文必须用 [1]、[2] 这样的编号标注，并在结尾保留“来源”列表和 URL。"
                    : "\n\n【角色权限边界】\n"
                    + "你当前不是“老巫师”博客管理员角色。禁止查看、创建、修改、发布、删除博客文章、分类、标签、评论或自动运维内容。"
                    + "不要声称已执行任何博客操作；如果用户要求操作博客内容，请说明需要切换到“老巫师”。"
                    + "你只能依据当前角色的 system prompt、上传的 SKILL.md/上下文、对话历史、searchSiteArticles 的只读站内摘要以及 webSearch 的压缩联网摘要进行问答。"
                    + "需要最新、当前或外部公开事实时，可调用 webSearch；每次只查一个具体问题，limit 默认 3。"
                    + "凡是使用联网结果陈述事实，正文必须用 [1]、[2] 这样的编号标注，并在结尾保留“来源”列表和 URL。";
            if (contextContent != null && !contextContent.isBlank()) {
                finalSystemPrompt = systemPrompt + personaGuard + "\n\n【上下文信息】\n" + contextContent;
            } else {
                finalSystemPrompt = systemPrompt + personaGuard;
            }

            // 加载服务端对话历史 + 压缩
            // 优先使用前端传入的 conversationId，每个新建对话有独立 ID
            String convId = body.has("conversationId") ? body.get("conversationId").asText("") : "";
            Mono<ConversationRef> convMono;
            if (!convId.isBlank()) {
                // 尝试按 ID 获取，不存在则创建
                String convName = convId.startsWith("conv-")
                        ? convId
                        : "conv-" + sessionId + "-" + personaId + "-" + convId;
                convMono = personaService.getConversationByGVK(convName)
                        .switchIfEmpty(personaService.createConversation(sessionId, personaId, convName))
                        .onErrorResume(e ->
                                personaService.createConversation(sessionId, personaId, convName));
            } else {
                convMono = personaService.getOrCreateConversation(sessionId, personaId);
            }
            return convMono
                    .onErrorResume(e -> {
                        log.warn("获取/创建对话失败，尝试直接创建新对话: {}", e.getMessage());
                        return personaService.createFallbackConversation(sessionId, personaId);
                    })
                    .flatMap(conv -> {
                        // 压缩过长的对话
                        personaService.compressConversation(conv);

                        // 构建完整消息列表
                        List<AgentService.ChatMessage> fullHistory = new ArrayList<>();
                        fullHistory.add(new AgentService.ChatMessage("system", finalSystemPrompt));

                        // 添加服务端已有的历史消息（从 JSON String 解析）
                        ArrayNode serverMessages = personaService.parseMessages(conv);
                        if (serverMessages != null) {
                            for (JsonNode msg : serverMessages) {
                                fullHistory.add(new AgentService.ChatMessage(
                                        msg.path("role").asText(),
                                        msg.path("content").asText()));
                            }
                        }

                        // 添加当前会话的用户消息（history 已包含 body.history + body.message）
                        // 跳过第一个 system 消息，从第一条 user 消息开始追加
                        for (AgentService.ChatMessage msg : history) {
                            if (!"system".equals(msg.role())) {
                                fullHistory.add(msg);
                            }
                        }

                        // 保存服务端持久化（追加本次用户消息）
                        // 异步保存，不阻塞流式响应
                        ArrayNode messagesJson = objectMapper.createArrayNode();
                        if (body.has("history") && body.get("history").isArray()) {
                            for (JsonNode msg : body.get("history")) {
                                if (!"system".equals(msg.path("role").asText())) {
                                    messagesJson.add(toPersistentMessageNode(msg, null));
                                }
                            }
                        }
                        messagesJson.add(toPersistentMessageNode(
                                "user", history.get(history.size() - 1).content(), Instant.now().toString()));
                        // 流式返回 + 结束时一次性保存用户消息 + AI 回复
                        Flux<String> chatStream = blogOpsEnabled
                                ? agentService.chat(fullHistory, requester)
                                : agentService.chatWithTools(fullHistory,
                                        Set.of("webSearch", "searchSiteArticles"), requester);
                        StringBuilder assistantResponse = new StringBuilder();

                        // 准备需要持久化的消息（用户输入）
                        ArrayNode allMessagesToSave = messagesJson;

                        Flux<String> savingStream = chatStream
                                .doOnNext(chunk -> assistantResponse.append(stripClientEvents(chunk)))
                                .doOnComplete(() -> {
                                    String reply = assistantResponse.toString();
                                    if (!reply.isBlank()) {
                                        ObjectNode assistNode = objectMapper.createObjectNode();
                                        assistNode.put("role", "assistant");
                                        assistNode.put("content", reply);
                                        assistNode.put("timestamp", Instant.now().toString());
                                        allMessagesToSave.add(assistNode);
                                    }
                                    if (allMessagesToSave.size() > 0) {
                                        personaService.appendMessages(conv, allMessagesToSave)
                                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                                .subscribe();
                                    }
                                });

                        return ServerResponse.ok()
                                .header("Content-Type", "text/event-stream; charset=utf-8")
                                .header("Cache-Control", "no-cache")
                                .body(BodyInserters.fromProducer(savingStream, String.class));
                    });
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
            msgNode.put("timestamp", Instant.now().toString());
            messagesJson.add(msgNode);
        }
        return personaService.appendMessages(sessionId, personaId, messagesJson)
                .then()
                .onErrorResume(e -> {
                    log.warn("保存对话消息失败（可能索引问题），忽略: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private ObjectNode toPersistentMessageNode(JsonNode msg, String fallbackTimestamp) {
        String timestamp = msg.path("timestamp").asText("");
        return toPersistentMessageNode(
                msg.path("role").asText(),
                msg.path("content").asText(),
                !timestamp.isBlank() ? timestamp : fallbackTimestamp);
    }

    private ObjectNode toPersistentMessageNode(String role, String content, String timestamp) {
        ObjectNode msgNode = objectMapper.createObjectNode();
        msgNode.put("role", role);
        msgNode.put("content", content);
        if (timestamp != null && !timestamp.isBlank()) {
            msgNode.put("timestamp", timestamp);
        }
        return msgNode;
    }

    private static String stripClientEvents(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return "";
        }
        return CLIENT_EVENT_LINE.matcher(chunk).replaceAll("");
    }

    private boolean isBlogOpsPersona(String personaId) {
        return personaId == null || personaId.isBlank() || "default".equals(personaId);
    }

    /**
     * GET /api/ai-assistant/me
     * 返回当前登录用户信息，用于替代客户端随机生成的 sessionId
     */
    private Mono<ServerResponse> handleGetMe(ServerRequest request) {
        return request.principal()
                .map(p -> {
                    String sid = "user-" + p.getName();
                    log.info("handleGetMe: authenticated user={}, sessionId={}", p.getName(), sid);
                    return Map.of(
                            "username", p.getName(),
                            "sessionId", sid,
                            "authenticated", true
                    );
                })
                .defaultIfEmpty(Map.of(
                        "sessionId", "",
                        "authenticated", false
                ))
                .flatMap(data -> ServerResponse.ok().bodyValue(data));
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
                    String icon = "/plugins/wizardj-ai-assistant/assets/logo.png";
                    String greeting = "你好，我是 Halo AI 智能助手";
                    try {
                        java.util.Optional<AiAssistantSetting> opt = settingFetcher.fetch("basic", AiAssistantSetting.class);
                        if (opt.isPresent()) {
                            AiAssistantSetting s = opt.get();
                            if (s.getPageTitle() != null && !s.getPageTitle().isBlank()) title = s.getPageTitle();
                            if (isSafePageIcon(s.getPageIcon())) icon = s.getPageIcon();
                            if (s.getGreeting() != null && !s.getGreeting().isBlank()) greeting = s.getGreeting();
                        }
                    } catch (Exception e) {
                        log.warn("读取配置失败，使用默认值", e);
                    }

                    // Persona 列表由前端通过 /api/ai-assistant/personas 异步加载
                    // 不在页面渲染时 block，避免扩展未就绪时出错

                    String html = CHAT_PAGE_HTML
                            .replace("__API_BASE__", PUBLIC_API_BASE)
                            .replace("<title>AI 智能助手</title>", "<title>" + escapeHtml(title) + "</title>")
                            .replace("你好，我是 Halo AI 智能助手",
                                    escapeHtml(greeting))
                            .replace("AI 智能助手", escapeHtml(title))
                            .replace("__PAGE_ICON__", escapeHtml(icon));
                    return ServerResponse.ok()
                            .header("Content-Type", "text/html; charset=utf-8")
                            .bodyValue(html);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(r -> r)
                .onErrorResume(e -> {
                    log.error("渲染聊天页面失败", e);
                    return ServerResponse.status(500)
                            .bodyValue(java.util.Map.of("error", "页面渲染失败，请稍后再试"));
                });
    }

    static boolean isSafePageIcon(String icon) {
        if (icon == null || icon.isBlank() || icon.length() > 400_000) return false;
        return icon.matches("^data:image/(?:png|jpeg|webp|svg\\+xml);base64,[A-Za-z0-9+/=]+$");
    }

    // ========== 原有路由保持不变 ==========    // ========== 原有路由保持不变 ==========

    private Mono<ServerResponse> handleAutoOpsTest(ServerRequest request) {
        if (!isBlogOpsPersona(request.queryParam("persona").orElse("default"))) {
            return ServerResponse.status(403).bodyValue(Map.of("success", false, "error", "当前角色仅支持聊天"));
        }
        return request.principal().flatMap(principal -> {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("mode", "test");
            payload.put("_requester", principal.getName());
            var pending = pendingActionService.create(
                    "autoOpsTest",
                    "自动运维测试确认",
                    "将抓取已配置的 RSS、调用 AI 生成内容，必要时创建禁止登录的专用作者，并生成待二次确认的草稿提案。",
                    RiskLevel.HIGH,
                    payload);
            return ServerResponse.ok().bodyValue(pending);
        });
    }


    private Mono<ServerResponse> handleChatPageRedirect(ServerRequest request) {
        return ServerResponse.permanentRedirect(URI.create(PUBLIC_API_BASE + "/chat-page")).build();
    }


    private Mono<ServerResponse> handleConfigCheck(ServerRequest request) {
        return Mono.fromCallable(() -> {
            StringBuilder sb = new StringBuilder("Config Check:\n");
            try {
                java.util.Optional<AiAssistantSetting> opt = settingFetcher.fetch("basic", AiAssistantSetting.class);
                if (opt.isPresent()) {
                    AiAssistantSetting s = opt.get();
                    sb.append("pageTitle: ").append(s.getPageTitle()).append("\n");
                    sb.append("greeting: ").append(s.getGreeting()).append("\n");
                    sb.append("pageIcon: ").append(s.getPageIcon()).append("\n");
                    sb.append("model: ").append(s.getModel()).append("\n");
                    sb.append("maxTokens: ").append(s.getMaxTokens()).append("\n");
                } else {
                    sb.append("Config not found (empty Optional)\n");
                }
            } catch (Exception e) {
                sb.append("Error reading config: ").append(e.getMessage()).append("\n");
            }
            return ServerResponse.ok()
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .bodyValue(sb.toString());
        }).flatMap(r -> r);
    }

    private Mono<ServerResponse> handleDailyPush(ServerRequest request) {
        return request.bodyToMono(JsonNode.class)
                .defaultIfEmpty(objectMapper.createObjectNode())
                .flatMap(body -> pushDailySummary(
                        body.path("secret").asText(""), body.path("channel").asText("")))
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
            setting.setProvider(s.getProvider());
            setting.setChatGptModel(s.getChatGptModel());
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

    // ========== 待确认操作端点 ==========

    private Mono<ServerResponse> handleGetPendingAction(ServerRequest request) {
        if (!isBlogOpsPersona(request.queryParam("persona").orElse("default"))) {
            return ServerResponse.status(403).bodyValue(Map.of("success", false, "error", "当前角色仅支持聊天"));
        }
        String id = request.pathVariable("id");
        return request.principal().flatMap(principal -> {
            PendingAction action = pendingActionService.get(id, principal.getName());
            if (action == null) {
                return ServerResponse.status(404).bodyValue(Map.of(
                        "found", false, "error", "操作不存在、已过期或不属于当前管理员"));
            }
            return ServerResponse.ok().bodyValue(Map.of(
                    "found", true, "id", action.getId(), "type", action.getType(),
                    "title", action.getTitle(), "summary", action.getSummary(),
                    "riskLevel", action.getRiskLevel().name().toLowerCase(),
                    "createdAt", action.getCreatedAt().toString(),
                    "expiresAt", action.getExpiresAt().toString()));
        });
    }

    Mono<ServerResponse> handleListPendingActions(ServerRequest request) {
        if (!isBlogOpsPersona(request.queryParam("persona").orElse("default"))) {
            return ServerResponse.ok().bodyValue(Map.of("actions", List.of()));
        }
        return request.principal().flatMap(principal -> {
            List<Map<String, Object>> actions = pendingActionService.listPending(principal.getName()).stream()
                    .map(action -> Map.<String, Object>of(
                            "id", action.getId(), "type", action.getType(),
                            "title", action.getTitle(), "summary", action.getSummary(),
                            "riskLevel", action.getRiskLevel().name().toLowerCase(),
                            "createdAt", action.getCreatedAt().toString(),
                            "expiresAt", action.getExpiresAt().toString()))
                    .toList();
            return ServerResponse.ok().bodyValue(Map.of("actions", actions));
        });
    }

    Mono<ServerResponse> handleConfirmAction(ServerRequest request) {
        if (!isBlogOpsPersona(request.queryParam("persona").orElse("default"))) {
            return ServerResponse.status(403).bodyValue(Map.of("success", false, "error", "当前角色仅支持聊天"));
        }
        String id = request.pathVariable("id");
        return request.principal().flatMap(principal -> Mono.fromCallable(
                        () -> pendingActionService.confirmAndExecute(id, principal.getName()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    boolean success = result.path("success").asBoolean();
                    String message = success
                            ? result.path("result").asText("操作已执行")
                            : result.path("error").asText(result.path("result").asText("操作执行失败"));
                    return ServerResponse.ok().bodyValue(Map.of(
                            "success", success,
                            "message", message,
                            "type", result.path("type").asText(""),
                            "result", result
                    ));
                }).switchIfEmpty(ServerResponse.ok().bodyValue(Map.of(
                        "success", false, "error", "操作不存在、已过期或已执行"))));
    }

    private Mono<ServerResponse> handleCancelAction(ServerRequest request) {
        if (!isBlogOpsPersona(request.queryParam("persona").orElse("default"))) {
            return ServerResponse.status(403).bodyValue(Map.of("success", false, "error", "当前角色仅支持聊天"));
        }
        String id = request.pathVariable("id");
        return request.principal().flatMap(principal -> {
            boolean cancelled = pendingActionService.cancel(id, principal.getName());
            if (!cancelled) {
                return ServerResponse.status(404).bodyValue(Map.of(
                        "success", false, "error", "操作不存在、已过期或不属于当前管理员"));
            }
            return ServerResponse.ok().bodyValue(Map.of(
                    "success", true, "message", "操作已取消"));
        });
    }
}
