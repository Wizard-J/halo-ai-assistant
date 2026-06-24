package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaController {

    private final PersonaService personaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouterFunction<ServerResponse> endpoints() {
        return RouterFunctions.route()
                .GET("/api/ai-assistant/personas", this::handleListPersonas)
                .POST("/api/ai-assistant/persona/upload", this::handleUploadPersona)
                .POST("/api/ai-assistant/persona/{id}/context", this::handleUploadContext)
                .DELETE("/api/ai-assistant/persona/{id}", this::handleDeletePersona)
                .GET("/api/ai-assistant/persona/{id}/conversations", this::handleListConversations)
                .DELETE("/api/ai-assistant/persona/{id}/conversations/{convId}",
                        this::handleDeleteConversation)
                .DELETE("/api/ai-assistant/session/conversations",
                        this::handleClearSessionConversations)
                .build();
    }

    /**
     * GET /api/ai-assistant/personas
     * 获取所有可用 Persona
     */
    private Mono<ServerResponse> handleListPersonas(ServerRequest request) {
        return personaService.listPersonas()
                .map(personas -> personas.stream().map(this::toPersonaSummary).toList())
                .flatMap(list -> ServerResponse.ok()
                        .bodyValue(Map.of("personas", list)))
                .onErrorResume(e -> {
                    log.error("获取 Persona 列表失败", e);
                    return ServerResponse.ok()
                            .bodyValue(Map.of("personas", java.util.Collections.emptyList()));
                });
    }

    /**
     * POST /api/ai-assistant/persona/upload
     * 上传 SKILL.md 文件创建新 Persona
     */
    private Mono<ServerResponse> handleUploadPersona(ServerRequest request) {
        return request.body(BodyExtractors.toMultipartData())
                .flatMap(parts -> {
                    Part filePart = parts.getFirst("file");
                    Part iconPart = parts.getFirst("icon");

                    if (!(filePart instanceof FilePart skillFile)) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "请上传 SKILL.md 文件"));
                    }

                    // 如果有头像图片一并上传
                    String iconUrl = null;
                    // 简单处理：头像上传在本实现中先使用请求参数
                    // 完整实现可走 Halo 附件 API
                    Mono<String> iconUrlMono = Mono.justOrEmpty(iconPart)
                            .filter(p -> p instanceof FilePart)
                            .map(p -> (FilePart) p)
                            .flatMap(this::uploadIcon)
                            .defaultIfEmpty("");

                    return iconUrlMono.flatMap(url ->
                            personaService.uploadSkill(skillFile, url.isEmpty() ? null : url)
                                    .flatMap(persona -> ServerResponse.ok()
                                            .bodyValue(toPersonaSummary(persona)))
                                    .onErrorResume(IllegalArgumentException.class, e ->
                                            ServerResponse.badRequest()
                                                    .bodyValue(Map.of("error", e.getMessage())))
                    );
                });
    }

    private Mono<String> uploadIcon(FilePart iconPart) {
        // TODO: 使用 Halo 附件 API 上传头像
        // 目前返回空，后续实现完整的附件上传
        log.info("收到头像上传请求: {}", iconPart.filename());
        return DataBufferUtils.join(iconPart.content())
                .map(dataBuffer -> {
                    DataBufferUtils.release(dataBuffer);
                    return "";
                });
    }

    /**
     * POST /api/ai-assistant/persona/{id}/context
     * 上传上下文内容（如 AGENTS.md），附加到该 Persona 的 system prompt
     */
    private Mono<ServerResponse> handleUploadContext(ServerRequest request) {
        String personaId = request.pathVariable("id");
        return request.body(BodyExtractors.toMultipartData())
                .flatMap(parts -> {
                    Part filePart = parts.getFirst("file");
                    if (!(filePart instanceof FilePart skillFile)) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "请上传上下文文件"));
                    }
                    return personaService.uploadContext(skillFile, personaId)
                            .flatMap(persona -> ServerResponse.ok()
                                    .bodyValue(Map.of("success", true, "message", "上下文已上传")))
                            .onErrorResume(IllegalArgumentException.class, e ->
                                    ServerResponse.badRequest()
                                            .bodyValue(Map.of("error", e.getMessage())));
                });
    }

    /**
     * DELETE /api/ai-assistant/persona/{id}
     */
    private Mono<ServerResponse> handleDeletePersona(ServerRequest request) {
        String id = request.pathVariable("id");
        return personaService.deletePersona(id)
                .then(ServerResponse.ok()
                        .bodyValue(Map.of("success", true, "message", "Persona 已删除")))
                .onErrorResume(IllegalArgumentException.class, e ->
                        ServerResponse.badRequest()
                                .bodyValue(Map.of("error", e.getMessage())));
    }

    /**
     * GET /api/ai-assistant/persona/{id}/conversations
     * 获取某 Persona 在某个 session 下的所有对话
     */
    private Mono<ServerResponse> handleListConversations(ServerRequest request) {
        String personaId = request.pathVariable("id");
        String sessionId = request.queryParam("sessionId").orElse("");
        if (sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "缺少 sessionId 参数"));
        }
        return personaService.listConversations(sessionId, personaId)
                .map(list -> list.stream().map(this::toConversationSummary).toList())
                .flatMap(list -> ServerResponse.ok()
                        .bodyValue(Map.of("conversations", list)));
    }

    /**
     * DELETE /api/ai-assistant/persona/{id}/conversations/{convId}
     */
    private Mono<ServerResponse> handleDeleteConversation(ServerRequest request) {
        String convId = request.pathVariable("convId");
        return personaService.deleteConversation(convId)
                .then(ServerResponse.ok()
                        .bodyValue(Map.of("success", true)));
    }

    /**
     * DELETE /api/ai-assistant/session/conversations
     * 清除某个 session 的所有对话
     */
    private Mono<ServerResponse> handleClearSessionConversations(ServerRequest request) {
        String sessionId = request.queryParam("sessionId").orElse("");
        if (sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "缺少 sessionId 参数"));
        }
        return personaService.deleteSessionConversations(sessionId)
                .then(ServerResponse.ok()
                        .bodyValue(Map.of("success", true)));
    }

    // ========== 数据转换 ==========

    private Map<String, Object> toPersonaSummary(PersonaDefinition p) {
        var spec = p.getSpec();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", p.getMetadata().getName());
        result.put("displayName", spec != null ? spec.getDisplayName() : "");
        result.put("description", spec != null ? spec.getDescription() : "");
        if (spec != null) result.put("iconUrl", spec.getIconUrl());
        result.put("brandColor", spec != null ? spec.getBrandColor() : "#2563eb");
        result.put("greeting", spec != null ? spec.getGreeting() : "");
        result.put("builtin", spec != null && spec.isBuiltin());
        return result;
    }

    private Map<String, Object> toConversationSummary(Conversation c) {
        var spec = c.getSpec();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", c.getMetadata().getName());
        result.put("title", spec != null ? spec.getTitle() : "");
        result.put("updatedAt", spec != null && spec.getUpdatedAt() != null
                ? spec.getUpdatedAt().toString() : "");
        result.put("messageCount", spec != null && spec.getMessages() != null
                ? 0 : 0);
        return result;
    }
}
