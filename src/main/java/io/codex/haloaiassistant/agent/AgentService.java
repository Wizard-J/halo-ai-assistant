package io.codex.haloaiassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;

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

    /**
     * 获取当前配置
     */
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
        if (depth > 5) {
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
                // Halo's ReactiveExtensionClient is reactive, while the current Tool contract is
                // synchronous. Run tool execution on a worker thread so its block() calls never
                // block Reactor Netty's event-loop thread.
                .publishOn(Schedulers.boundedElastic())
                .flatMapMany(response -> {
                    try {
                        JsonNode root = objectMapper.readTree(response);
                        JsonNode choice = root.path("choices").get(0);
                        JsonNode message = choice.path("message");

                        String role = message.path("role").asText();
                        String content = message.path("content").asText("");

                        // 检查是否有 tool_calls
                        JsonNode toolCalls = message.path("tool_calls");
                        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                            StringBuilder resultBuilder = new StringBuilder();
                            List<ChatMessage> updatedMessages = new java.util.ArrayList<>(messages);

                            // 添加 assistant 消息（包含 tool_calls）
                            ObjectNode assistantMsg = objectMapper.createObjectNode();
                            assistantMsg.put("role", "assistant");
                            assistantMsg.put("content", content);
                            assistantMsg.set("tool_calls", toolCalls);
                            updatedMessages.add(new ChatMessage("assistant", content, toolCalls));

                            for (JsonNode tc : toolCalls) {
                                String functionName = tc.path("function").path("name").asText();
                                String arguments = tc.path("function").path("arguments").asText();
                                String toolCallId = tc.path("id").asText();

                                log.info("调用工具: {} 参数: {}", functionName, arguments);

                                var toolOpt = toolRegistry.getByName(functionName);
                                if (toolOpt.isEmpty()) {
                                    String errMsg = "未知工具: " + functionName;
                                    updatedMessages.add(new ChatMessage("tool", errMsg, toolCallId, functionName));
                                    resultBuilder.append("工具 ").append(functionName).append(" 不存在。\n");
                                    continue;
                                }

                                try {
                                    JsonNode argsNode = objectMapper.readTree(arguments);
                                    String result = toolOpt.get().execute(argsNode);
                                    updatedMessages.add(new ChatMessage("tool", result, toolCallId, functionName));
                                    resultBuilder.append("工具 ").append(functionName).append(" 执行成功。\n");
                                } catch (Exception e) {
                                    String errMsg = "执行失败: " + e.getMessage();
                                    updatedMessages.add(new ChatMessage("tool", errMsg, toolCallId, functionName));
                                    resultBuilder.append("工具 ").append(functionName).append(" 执行失败: ").append(e.getMessage()).append("\n");
                                }
                            }

                            // 递归调用，让 AI 根据工具结果生成回复
                            return doChat(updatedMessages, depth + 1);
                        }

                        // 没有 tool_calls，直接返回回复
                        return Flux.just(content);

                    } catch (Exception e) {
                        log.error("解析响应失败", e);
                        return Flux.just("[错误] 解析 AI 响应失败: " + e.getMessage()
                                + "\n原始响应: " + response.substring(0, Math.min(500, response.length())));
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
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
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
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
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

    private String buildRequestBody(List<ChatMessage> messages, String toolsJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", currentSetting.getModel());

        // System 提示词
        ArrayNode msgs = root.putArray("messages");
        ObjectNode systemMsg = msgs.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", currentSetting.getSystemPrompt()
                + "\n调用工具后必须忠实依据工具返回结果回答。若工具返回失败或错误，"
                + "必须原样说明关键错误原因，不得声称操作已成功，也不得编造服务器维护等原因。");

        for (ChatMessage msg : messages) {
            ObjectNode msgNode = msgs.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());

            // 处理 tool_call_id
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
            // 处理 name
            if (msg.name() != null) {
                msgNode.put("name", msg.name());
            }
            // 处理 tool_calls
            if (msg.toolCalls() != null) {
                msgNode.set("tool_calls", msg.toolCalls());
            }
        }

        root.put("max_tokens", currentSetting.getMaxTokens());

        // 添加 tools 声明
        try {
            root.set("tools", objectMapper.readTree(toolsJson));
        } catch (Exception e) {
            log.warn("解析 tools JSON 失败", e);
        }

        return root.toPrettyString();
    }

    /**
     * 对话消息记录
     */
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
}
