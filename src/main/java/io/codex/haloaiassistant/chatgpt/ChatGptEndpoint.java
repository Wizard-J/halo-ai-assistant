package io.codex.haloaiassistant.chatgpt;

import io.codex.haloaiassistant.agent.AgentService.ChatMessage;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import io.codex.haloaiassistant.endpoint.AiChatEndpoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** Console API group is restricted to Halo super administrators, like the existing chat endpoint. */
@Component
@RequiredArgsConstructor
public class ChatGptEndpoint implements CustomEndpoint {
    private final ChatGptCredentials credentials;
    private final ChatGptResponsesClient responses;
    private final ReactiveSettingFetcher settings;
    private final ChatGptModelWarmup modelWarmup;

    @Override
    public GroupVersion groupVersion() { return AiChatEndpoint.API_VERSION; }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                .GET("/chatgpt/setup", request -> authenticated(request, () -> Mono.fromCallable(() -> {
                    try (var stream = ChatGptEndpoint.class.getResourceAsStream("/docs/CHATGPT_SUBSCRIPTION.md")) {
                        if (stream == null) throw new IllegalStateException("授权说明未打包，请重新构建插件");
                        return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }).subscribeOn(Schedulers.boundedElastic()).flatMap(text -> ServerResponse.ok()
                        .header("Content-Type", "text/plain; charset=utf-8").bodyValue(text))))
                .GET("/chatgpt/status", request -> authenticated(request, () -> status()))
                .POST("/chatgpt/warmup", request -> authenticated(request, () -> {
                    modelWarmup.pageOpened();
                    return ServerResponse.accepted().build();
                }))
                .GET("/chatgpt/models", request -> authenticated(request, () -> responses.models()
                        .flatMap(models -> ServerResponse.ok().header("Cache-Control", "no-store")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .bodyValue("{\"models\":" + models + "}"))))
                .POST("/chatgpt/check", request -> authenticated(request, () -> check()))
                .POST("/chatgpt/disconnect", request -> authenticated(request, () -> Mono
                        .fromCallable(credentials::disconnect).subscribeOn(Schedulers.boundedElastic())
                        .doOnNext(result -> {
                            modelWarmup.endTransportLease();
                            responses.invalidateModels();
                        })
                        .flatMap(result -> ServerResponse.ok().bodyValue(result))))
                .build();
    }

    private Mono<ServerResponse> status() {
        return settings.fetch("basic", AiAssistantSetting.class).defaultIfEmpty(new AiAssistantSetting())
                .flatMap(setting -> Mono.fromCallable(() -> {
                    Map<String, Object> result = new LinkedHashMap<>(credentials.status());
                    result.put("provider", setting.getProvider() == null ? "api" : setting.getProvider());
                    result.put("model", setting.getChatGptModel() == null ? "" : setting.getChatGptModel());
                    result.put("usageUrl", "https://chatgpt.com/settings/usage");
                    return result;
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnNext(result -> {
                    if (Boolean.TRUE.equals(result.get("connected")) && "chatgpt".equals(result.get("provider")))
                        modelWarmup.pageOpened();
                })
                .flatMap(result -> ServerResponse.ok().header("Cache-Control", "no-store").bodyValue(result));
    }

    private Mono<ServerResponse> check() {
        return settings.fetch("basic", AiAssistantSetting.class).defaultIfEmpty(new AiAssistantSetting())
                .flatMap(setting -> responses.chat(List.of(new ChatMessage("user", "Reply with exactly: Connected.")),
                                setting, Set.of(), "system").collectList())
                .flatMap(chunks -> {
                    String text = String.join("", chunks);
                    boolean success = !text.isBlank() && !text.contains("[ChatGPT 订阅错误]");
                    return ServerResponse.ok().header("Cache-Control", "no-store")
                            .bodyValue(Map.of("success", success, "message", success
                                    ? "订阅请求已完成，正在使用 ChatGPT 套餐" : text));
                });
    }

    private Mono<ServerResponse> authenticated(ServerRequest request,
                                                java.util.function.Supplier<Mono<ServerResponse>> action) {
        return request.principal().flatMap(principal -> Mono.defer(action)
                        .onErrorResume(e -> ServerResponse.badRequest().bodyValue(
                                Map.of("error", ChatGptCredentials.safeError(e)))))
                .switchIfEmpty(ServerResponse.status(401).bodyValue(Map.of("error", "请先登录 Halo Console")));
    }
}
