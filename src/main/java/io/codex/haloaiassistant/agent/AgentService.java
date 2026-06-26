package io.codex.haloaiassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
public class AgentService {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiAssistantSetting currentSetting;

    public AgentService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void updateSetting(AiAssistantSetting setting) {
        this.currentSetting = setting;
    }

    public AiAssistantSetting getCurrentSetting() {
        if (currentSetting == null) {
            currentSetting = new AiAssistantSetting();
        }
        return currentSetting;
    }

    /** Generate plain content without exposing blog tools. Used by background automation. */
    public Mono<String> generateText(AiAssistantSetting setting, String systemPrompt,
                                      String userPrompt, int maxTokens) {
        String endpoint = setting.getApiEndpoint().replaceAll("/+$", "");
        if (!endpoint.endsWith("/v1")) {
            endpoint += "/v1";
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", setting.getModel());
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        return WebClient.create().post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + setting.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(raw -> response.statusCode().isError()
                                ? Mono.error(new AiApiException(response.statusCode().value(), raw))
                                : Mono.just(raw)))
                .map(raw -> {
                    try {
                        return objectMapper.readTree(raw).path("choices").path(0)
                                .path("message").path("content").asText("");
                    } catch (Exception e) {
                        throw new IllegalStateException("解析自动文章响应失败", e);
                    }
                });
    }

    /**
     * 发送对话消息，支持 function calling 循环
     * 如果 messages 第一条是 role=system，使用它作为 system prompt，否则使用默认配置
     */
    public Flux<String> chat(List<ChatMessage> messages) {
        return Flux.defer(() -> {
            if (currentSetting == null || currentSetting.getApiKey() == null || currentSetting.getApiKey().isBlank()) {
                return Flux.just("[错误] 请先在插件配置中填写 API Key");
            }
            if (currentSetting.getApiEndpoint() == null || currentSetting.getApiEndpoint().isBlank()) {
                return Flux.just("[错误] 请先在插件配置中填写 Base URL");
            }
            if (currentSetting.getModel() == null || currentSetting.getModel().isBlank()) {
                return Flux.just("[错误] 请先在插件配置中填写模型名称");
            }
            return doChat(messages, 0);
        });
    }

    private Flux<String> doChat(List<ChatMessage> messages, int depth) {
        if (depth > 30) {
            return Flux.just("[系统] 工具调用次数过多，请简化指令");
        }

        String endpoint = currentSetting.getApiEndpoint().replaceAll("/+$", "");
        if (!endpoint.endsWith("/v1")) {
            endpoint = endpoint + "/v1";
        }

        String toolsJson = toolRegistry.generateToolsJson();

        return WebClient.create()
                .post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + currentSetting.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(buildRequestBody(messages, toolsJson))
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            if (response.statusCode().isError()) {
                                return Mono.error(new AiApiException(response.statusCode().value(), body));
                            }
                            return Mono.just(body);
                        }))
                .flatMapMany(body -> {
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode choice = root.path("choices").path(0);
                        JsonNode message = choice.path("message");
                        // 丢弃 reasoning_content（DeepSeek 模型的独立思考字段）
                        String reasoning = message.path("reasoning_content").asText();
                        if (!reasoning.isBlank()) {
                            log.debug("DeepSeek reasoning: {}", reasoning.substring(0, Math.min(reasoning.length(), 200)));
                        }
                        JsonNode toolCalls = message.path("tool_calls");

                        if (toolCalls.isArray() && toolCalls.size() > 0) {
                            // 处理工具调用
                            // 工具调用期间不流输出，静默执行，只输出最终结果
                            return Flux.<String>empty()
                                    .concatWith(Flux.defer(() -> {
                                        List<ChatMessage> newMessages = new java.util.ArrayList<>(messages);
                                        // 添加助手响应（含 tool_calls）
                                        newMessages.add(new ChatMessage("assistant",
                                                message.path("content").asText(),
                                                toolCalls));
                                        // 执行每个工具并添加结果（在 boundedElastic 线程池上执行，避免阻塞 reactor 线程）
                                        return Flux.fromIterable(toolCalls)
                                                .flatMap(tc -> {
                                                    String id = tc.path("id").asText();
                                                    String name = tc.path("function").path("name").asText();
                                                    String args = tc.path("function").path("arguments").asText();
                                                    Tool tool = toolRegistry.getByName(name).orElse(null);
                                                    return Mono.fromCallable(() -> {
                                                                if (tool != null) {
                                                                    try {
                                                                        JsonNode argsJson = objectMapper.readTree(args);
                                                                        log.info("调用工具: {} args={}", name, args);
                                                                        return tool.execute(argsJson);
                                                                    } catch (Exception e) {
                                                                        log.error("工具执行失败: {}", name, e);
                                                                        return "工具执行出错: " + e.getMessage();
                                                                    }
                                                                }
                                                                return "未知工具: " + name;
                                                            })
                                                            .subscribeOn(Schedulers.boundedElastic())
                                                            .map(result -> new Object[]{id, name, result});
                                                })
                                                .collectList()
                                                .flatMapMany(results -> {
                                                    for (Object[] r : results) {
                                                        newMessages.add(new ChatMessage("tool",
                                                                (String) r[2], (String) r[0], (String) r[1]));
                                                    }
                                                    return doChat(newMessages, depth + 1);
                                                });
                                    }));
                        }

                        // 正常文本响应
                        String content = message.path("content").asText("");
                        if (content.isBlank()) {
                            String finishReason = choice.path("finish_reason").asText("");
                            String refusal = message.path("refusal").asText("");
                            if (!refusal.isBlank()) {
                                return Flux.just("[AI 拒绝回答] " + refusal);
                            }
                            log.warn("AI API 返回空内容，finish_reason={}，响应: {}",
                                    finishReason, abbreviate(body, 1000));
                            return Flux.just("[错误] AI 服务返回了空内容"
                                    + (finishReason.isBlank() ? "" : "（finish_reason: " + finishReason + "）"));
                        }
                        return Flux.just(content);
                    } catch (Exception e) {
                        log.error("解析 AI 响应失败", e);
                        return Flux.just("[错误] 解析 AI 响应失败");
                    }
                })
                .onErrorResume(AiApiException.class, error -> {
                    log.warn("AI API 请求失败，状态码: {}，响应: {}",
                            error.statusCode, abbreviate(error.responseBody, 1000));
                    return Flux.just(formatApiError(error));
                })
                .onErrorResume(error -> {
                    log.error("调用 AI API 失败", error);
                    return Flux.just("[错误] 无法连接 AI API：" + safeMessage(error)
                            + "\n请检查 Base URL 及服务器网络。");
                });
    }

    private String formatToolCallResult(JsonNode message) {
        String content = message.path("content").asText();
        if (content != null && !content.isBlank()) {
            return content;
        }
        return "";
    }

    private String formatApiError(AiApiException error) {
        String detail = extractErrorMessage(error.responseBody);
        String hint = switch (error.statusCode) {
            case 400 -> "请求参数被模型服务拒绝";
            case 401, 403 -> "API Key 无效或没有权限";
            case 402 -> "API 账户余额不足";
            case 404 -> "Base URL 或模型名称不正确";
            case 429 -> "请求过于频繁或已达限额";
            default -> "模型服务返回异常";
        };
        return String.format("[AI API 错误 %d] %s%s", error.statusCode, hint,
                detail.isBlank() ? "" : "\n详情：" + detail);
    }

    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            return message.isBlank() ? abbreviate(responseBody, 500) : abbreviate(message, 500);
        } catch (Exception ignored) {
            return abbreviate(responseBody, 500);
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : abbreviate(message, 500);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 构建 API 请求体，支持外部传入的 system message
     */
    private String buildRequestBody(List<ChatMessage> messages, String toolsJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", currentSetting.getModel());

        ArrayNode msgs = root.putArray("messages");

        // 检查是否已有 system 消息（由外部传入）
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> "system".equals(m.role()));

        if (!hasSystemMessage) {
            // 没有外部 system 消息时，使用默认配置
            ObjectNode systemMsg = msgs.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", currentSetting.getSystemPrompt()
                    + "\n\n【硬性规则 - 违者任务失败】\n"
                    + "- 分类/打标签：你的第一句回复必须包含 autoTagArticles 工具调用。不许先说废话，直接调！\n"
                    + "- 创建文章：直接调 createArticle。不反问、不确认、不商量。\n"
                    + "- 永远不说「我先看看」「让我检查」「我发现了一个工具」等废话。直接行动。\n"
                    + "- 回复上限 3 句话。超过就是违规。");
        }

        for (ChatMessage msg : messages) {
            ObjectNode msgNode = msgs.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());

            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
            if (msg.name() != null) {
                msgNode.put("name", msg.name());
            }
            if (msg.toolCalls() != null) {
                msgNode.set("tool_calls", msg.toolCalls());
            }
        }

        root.put("max_tokens", currentSetting.getMaxTokens());

        try {
            root.set("tools", objectMapper.readTree(toolsJson));
        } catch (Exception e) {
            log.warn("解析 tools JSON 失败", e);
        }

        return root.toPrettyString();
    }

    public record ChatMessage(
            String role,
            String content,
            String toolCallId,
            String name,
            JsonNode toolCalls
    ) {
        public ChatMessage(String role, String content) {
            this(role, content, null, null, null);
        }

        public ChatMessage(String role, String content, JsonNode toolCalls) {
            this(role, content, null, null, toolCalls);
        }

        public ChatMessage(String role, String content, String toolCallId, String name) {
            this(role, content, toolCallId, name, null);
        }
    }

    private static class AiApiException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        private AiApiException(int statusCode, String responseBody) {
            super("AI API returned HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
