package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.JsonNode;
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
                .GET("/api/ai-assistant/persona/{id}/conversations/current", this::handleGetCurrentConversation)
                .GET("/api/ai-assistant/persona/{id}/conversations/{convId}",
                        this::handleGetConversation)
                .DELETE("/api/ai-assistant/persona/{id}/conversations/{convId}",
                        this::handleDeleteConversation)
                .PUT("/api/ai-assistant/persona/{id}/conversations/{convId}/rename",
                        this::handleRenameConversation)
                .DELETE("/api/ai-assistant/session/conversations",
                        this::handleClearSessionConversations)
                .POST("/api/ai-assistant/persona/{id}/context/refine", this::handleRefineContext)
                .GET("/api/ai-assistant/persona/{id}/context/download",
                        this::handleDownloadContext)
                .PUT("/api/ai-assistant/persona/{id}",
                        this::handleUpdatePersona)
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
                            .onErrorResume(e -> {
                                log.warn("上传上下文失败: {}", e.getMessage());
                                return ServerResponse.badRequest()
                                        .bodyValue(Map.of("error", "上传失败: " + e.getMessage()));
                            });
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
                .onErrorResume(e -> {
                    log.warn("listConversations 失败，返回空列表: {}", e.getMessage());
                    return Mono.just(java.util.Collections.emptyList());
                })
                .map(list -> list.stream().map(this::toConversationSummary).toList())
                .flatMap(list -> ServerResponse.ok()
                        .bodyValue(Map.of("conversations", list)));
    }

    /**
     * GET /api/ai-assistant/persona/{id}/conversations/current?sessionId=xxx
     * 返回最新对话的完整消息列表
     */
    private Mono<ServerResponse> handleGetCurrentConversation(ServerRequest request) {
        String personaId = request.pathVariable("id");
        String sessionId = request.queryParam("sessionId").orElse("");
        if (sessionId.isBlank()) {
            return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "缺少 sessionId 参数"));
        }
        // 仅返回已有对话，不自动创建
        return personaService.listConversations(sessionId, personaId)
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return ServerResponse.ok()
                                .bodyValue(Map.of("id", "", "messages", java.util.Collections.emptyList(),
                                        "title", "", "updatedAt", ""));
                    }
                    ConversationRef conv = list.get(0);
                    var spec = conv.getSpec();
                    java.util.List<Map<String, String>> msgs = new java.util.ArrayList<>();
                    ArrayNode raw = personaService.parseMessages(conv);
                    if (raw != null) {
                        for (JsonNode msg : raw) {
                            Map<String, String> m = new java.util.HashMap<>();
                            m.put("role", msg.path("role").asText());
                            m.put("content", msg.path("content").asText());
                            msgs.add(m);
                        }
                    }
                    return ServerResponse.ok().bodyValue(Map.of(
                            "id", conv.getMetadata().getName(),
                            "messages", msgs,
                            "title", spec != null ? spec.getTitle() : "",
                            "updatedAt", spec != null && spec.getUpdatedAt() != null
                                    ? spec.getUpdatedAt().toString() : ""
                    ));
                });
    }

    /**
     * GET /api/ai-assistant/persona/{id}/conversations/{convId}
     * 获取单条对话的完整消息
     */
    private Mono<ServerResponse> handleGetConversation(ServerRequest request) {
        String convId = request.pathVariable("convId");
        return personaService.getConversation(convId)
                .flatMap(conv -> {
                    var spec = conv.getSpec();
                    java.util.List<Map<String, String>> msgs = new java.util.ArrayList<>();
                    ArrayNode raw = personaService.parseMessages(conv);
                    if (raw != null) {
                        for (JsonNode msg : raw) {
                            Map<String, String> m = new java.util.HashMap<>();
                            m.put("role", msg.path("role").asText());
                            m.put("content", msg.path("content").asText());
                            msgs.add(m);
                        }
                    }
                    return ServerResponse.ok().bodyValue(Map.of(
                            "id", conv.getMetadata().getName(),
                            "messages", msgs,
                            "title", spec != null ? spec.getTitle() : "",
                            "updatedAt", spec != null && spec.getUpdatedAt() != null
                                    ? spec.getUpdatedAt().toString() : ""
                    ));
                })
                .onErrorResume(e -> {
                    log.warn("获取对话 {} 失败: {}", convId, e.getMessage());
                    return ServerResponse.notFound().build();
                });
    }

    /**
     * DELETE /api/ai-assistant/persona/{id}/conversations/{convId}
     */
    private Mono<ServerResponse> handleDeleteConversation(ServerRequest request) {
        String convId = request.pathVariable("convId");
        return personaService.deleteConversation(convId)
                .then(ServerResponse.ok()
                        .bodyValue(Map.of("success", true)))
                .onErrorResume(e -> {
                    log.warn("删除对话失败: {}", e.getMessage());
                    return ServerResponse.status(500)
                            .bodyValue(Map.of("success", false, "error", e.getMessage()));
                });
    }

    /**
     * PUT /api/ai-assistant/persona/{id}/conversations/{convId}/rename
     */
    private Mono<ServerResponse> handleRenameConversation(ServerRequest request) {
        String convId = request.pathVariable("convId");
        return request.bodyToMono(Map.class)
                .flatMap(body -> {
                    String newTitle = (String) body.get("title");
                    if (newTitle == null || newTitle.isBlank()) {
                        return ServerResponse.badRequest()
                                .bodyValue(Map.of("error", "标题不能为空"));
                    }
                    return personaService.updateConversationTitle(convId, newTitle)
                            .then(ServerResponse.ok().bodyValue(Map.of("success", true)))
                            .onErrorResume(e -> ServerResponse.badRequest()
                                    .bodyValue(Map.of("error", e.getMessage())));
                });
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
                        .bodyValue(Map.of("success", true)))
                .onErrorResume(e -> {
                    log.warn("清除session对话失败，仍返回成功: {}", e.getMessage());
                    return ServerResponse.ok()
                            .bodyValue(Map.of("success", true));
                });
    }



    /**
     * GET /api/ai-assistant/persona/{id}/context/download
     * 导出上下文：自动提炼后直接返回合并后的 AGENTS.md（不追加原始对话）
     */
    private Mono<ServerResponse> handleDownloadContext(ServerRequest request) {
        String personaId = request.pathVariable("id");
        String sessionId = request.queryParam("sessionId").orElse("");

        // 自动提炼：将未处理过的对话合并到 AGENTS.md（静默处理）
        Mono<Void> autoRefine = Mono.justOrEmpty(sessionId)
                .filter(s -> !s.isBlank())
                .flatMap(s -> personaService.refineContext(personaId, sessionId)
                        .doOnSuccess(merged -> log.info("导出前自动提炼成功"))
                        .onErrorResume(e -> {
                            log.debug("导出前自动提炼跳过: {}", e.getMessage());
                            return Mono.empty();
                        })
                )
                .then();

        // 获取当前 AGENTS.md（提炼后已是最新版）
        Mono<String> contextMono = personaService.getPersona(personaId)
                .map(p -> {
                    if (p.getSpec() == null) return "# (无数据)\n\nPersona 数据异常。";
                    String ctx = p.getSpec().getContextContent();
                    return ctx != null && !ctx.isBlank()
                            ? ctx : "# (无上下文)\n\n尚未上传上下文文件。";
                });

        // 先自动提炼，再直接返回合并后的 AGENTS.md
        return autoRefine.then(contextMono)
                .flatMap(content -> {
                    String filename = "context-" + personaId + ".md";
                    return ServerResponse.ok()
                            .header("Content-Disposition",
                                    "attachment; filename=\"" + filename + "\"")
                            .contentType(MediaType.TEXT_PLAIN)
                            .bodyValue(content);
                });
    }

    /**
     * POST /api/ai-assistant/persona/{id}/context/refine
     */    /**
     * POST /api/ai-assistant/persona/{id}/context/refine
     */
    private Mono<ServerResponse> handleRefineContext(ServerRequest request) {
        String personaId = request.pathVariable("id");
        String sessionId = request.queryParam("sessionId").orElse("");
        if (sessionId.isBlank()) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "缺少 sessionId 参数"));
        }
        return personaService.refineContext(personaId, sessionId)
                .flatMap(mergedContent -> ServerResponse.ok()
                        .bodyValue(Map.of("success", true, "message", "上下文已拼接", "content", mergedContent)))
                .onErrorResume(e -> {
                    log.error("提炼上下文失败 personaId={}", personaId, e);
                    return ServerResponse.badRequest()
                            .bodyValue(Map.of("error", e.getMessage() != null ? e.getMessage() : "提炼失败"));
                });
    }

    /**
     * PUT /api/ai-assistant/persona/{id}
     */
    @SuppressWarnings("unchecked")
    private Mono<ServerResponse> handleUpdatePersona(ServerRequest request) {
        String personaId = request.pathVariable("id");
        return request.bodyToMono(Map.class)
                .flatMap(fields -> {
                    return personaService.updatePersona(personaId, fields)
                            .flatMap(persona -> {
                                java.util.Map<String, Object> result = new java.util.HashMap<>();
                                result.put("success", true);
                                result.put("persona", toPersonaSummary((PersonaDefinition) persona));
                                return ServerResponse.ok().bodyValue(result);
                            });
                })
                .onErrorResume(e -> {
                    log.error("更新 Persona 失败", e);
                    java.util.Map<String, Object> err = new java.util.HashMap<>();
                    err.put("error", ((Throwable)e).getMessage() != null ? ((Throwable)e).getMessage() : "更新失败");
                    return ServerResponse.badRequest().bodyValue(err);
                });
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
        result.put("thinkingPhrases", spec != null && spec.getThinkingPhrases() != null ? spec.getThinkingPhrases() : "[]");
        result.put("builtin", spec != null && spec.isBuiltin());
        return result;
    }

    private Map<String, Object> toConversationSummary(ConversationRef c) {
        var spec = c.getSpec();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", c.getMetadata().getName());
        result.put("title", spec != null ? spec.getTitle() : "");
        result.put("updatedAt", spec != null && spec.getUpdatedAt() != null
                ? spec.getUpdatedAt().toString() : "");
        int msgCount = 0;
        if (spec != null && spec.getMessages() != null) {
            try {
                var arr = objectMapper.readTree(spec.getMessages());
                msgCount = arr.isArray() ? arr.size() : 0;
            } catch (Exception e) { /* ignore */ }
        }
        result.put("messageCount", msgCount);
        return result;
    }
}
