package io.codex.haloaiassistant.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.autoops.AutoOpsService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiChatEndpoint {

    private static final String CHAT_PAGE_HTML = loadChatPage();

    private final ReactiveSettingFetcher settingFetcher;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;
    private final AutoOpsService autoOpsService;

    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
                // AI 对话 API
                .POST("/api/ai-assistant/chat", this::handleChat)
                // 获取工具列表
                .GET("/api/ai-assistant/tools", this::handleGetTools)
                // 对话页面（可直接浏览器访问）
                .GET("/api/ai-assistant/chat-page", this::handleChatPage)
                .POST("/api/ai-assistant/auto-ops/test", this::handleAutoOpsTest)
                .build();
    }

    private Mono<ServerResponse> handleChat(ServerRequest request) {
        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> settingFetcher.fetch("basic", AiAssistantSetting.class)
                        .doOnNext(agentService::updateSetting)
                        .thenReturn(body))
                .flatMap(body -> {
                    String message = body.has("message") ? body.get("message").asText() : "";
                    if (message.isBlank()) {
                        return ServerResponse.badRequest().bodyValue(Map.of("error", "消息不能为空"));
                    }

                    // 历史消息
                    List<AgentService.ChatMessage> history = new ArrayList<>();
                    if (body.has("history") && body.get("history").isArray()) {
                        for (JsonNode msg : body.get("history")) {
                            history.add(new AgentService.ChatMessage(
                                    msg.get("role").asText(),
                                    msg.get("content").asText()
                            ));
                        }
                    }

                    // 添加当前消息
                    history.add(new AgentService.ChatMessage("user", message));

                    // 流式返回
                    return ServerResponse.ok()
                            .header("Content-Type", "text/event-stream; charset=utf-8")
                            .header("Cache-Control", "no-cache")
                            .body(BodyInserters.fromProducer(
                                    agentService.chat(history), String.class
                            ));
                });
    }

    private Mono<ServerResponse> handleGetTools(ServerRequest request) {
        return ServerResponse.ok()
                .bodyValue(Map.of("tools", toolRegistry.getAll().stream()
                        .map(t -> Map.of("name", t.getName(), "description", t.getDescription()))
                        .toList()));
    }

    private Mono<ServerResponse> handleChatPage(ServerRequest request) {
        return ServerResponse.ok()
                .header("Content-Type", "text/html; charset=utf-8")
                .bodyValue(CHAT_PAGE_HTML);
    }

    private Mono<ServerResponse> handleAutoOpsTest(ServerRequest request) {
        return autoOpsService.testNow()
                .flatMap(result -> ServerResponse.ok().bodyValue(result))
                .onErrorResume(error -> ServerResponse.status(500).bodyValue(Map.of(
                        "success", false,
                        "error", error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage()
                )));
    }

    private static String loadChatPage() {
        try (InputStream input = AiChatEndpoint.class.getResourceAsStream("/chat-page.html")) {
            if (input == null) {
                throw new IllegalStateException("Missing chat-page.html resource");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load chat-page.html", error);
        }
    }

}
