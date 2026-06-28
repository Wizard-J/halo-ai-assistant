package io.codex.haloaiassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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

    private static final int MAX_TOOL_DEPTH = 30;

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

    // ========== generateText（非流式，用于后台自动运维） ==========

    /** Generate plain content without exposing blog tools. Used by background automation. */
    public Mono<String> generateText(AiAssistantSetting setting, String systemPrompt,
                                      String userPrompt, int maxTokens) {
        String endpoint = normalizeEndpoint(setting.getApiEndpoint());
        String model = setting.getModel();
        int promptChars = (systemPrompt == null ? 0 : systemPrompt.length())
                + (userPrompt == null ? 0 : userPrompt.length());
        String requestBody = buildGenerateTextRequestBody(model, systemPrompt, userPrompt, maxTokens);

        return WebClient.create().post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + setting.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(raw -> {
                            if (response.statusCode().isError()) {
                                log.warn("自动运维 AI API 请求失败，状态码: {}, model: {}, promptChars: {}, 响应: {}",
                                        response.statusCode().value(), model, promptChars, abbreviate(raw, 1000));
                                return Mono.error(new AiApiException(response.statusCode().value(), raw));
                            }
                            return Mono.just(raw);
                        }))
                .map(raw -> {
                    String content;
                    try {
                        content = objectMapper.readTree(raw).path("choices").path(0)
                                .path("message").path("content").asText("");
                    } catch (Exception e) {
                        throw new IllegalStateException("解析自动文章响应失败", e);
                    }
                    if (content.isBlank()) {
                        throw new IllegalStateException("自动文章响应内容为空: " + abbreviate(raw, 500));
                    }
                    return content;
                });
    }

    private String buildGenerateTextRequestBody(String model, String systemPrompt, String userPrompt, int maxTokens) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return body.toString();
    }

    // ========== 前台聊天（流式） ==========

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

    // ========== 流式对话主路径 ==========

    private Flux<String> doChat(List<ChatMessage> messages, int depth) {
        if (depth > MAX_TOOL_DEPTH) {
            return Flux.just("[系统] 工具调用次数过多，请简化指令");
        }

        String endpoint = normalizeEndpoint(currentSetting.getApiEndpoint());
        String toolsJson = toolRegistry.generateToolsJson();
        String requestBody = buildRequestBody(messages, toolsJson, true);
        StreamState state = new StreamState();

        Flux<String> contentStream = streamChatCompletion(endpoint, requestBody, state);

        return contentStream.concatWith(Flux.defer(() -> {
            // 流结束后处理工具调用或空内容
            if (state.hasToolCalls()) {
                return continueAfterToolCalls(messages, state, depth);
            }
            if (state.content.length() == 0 && state.refusal != null && !state.refusal.isBlank()) {
                return Flux.just("[AI 拒绝回答] " + state.refusal);
            }
            if (state.content.length() == 0 && !state.hasToolCalls()) {
                String reasonHint = state.finishReason != null && !state.finishReason.isBlank()
                        ? "（finish_reason: " + state.finishReason + "）" : "";
                return Flux.just("[错误] AI 服务返回了空内容" + reasonHint);
            }
            return Flux.empty();
        }))
        .onErrorResume(AiApiException.class, error -> {
            // 尝试检测是否因 stream: true 不被支持，回退非流式
            if (error.statusCode == 400 && extractErrorMessage(error.responseBody).toLowerCase().contains("stream")) {
                log.warn("当前模型服务可能不支持 stream，回退到非流式调用");
                return doChatNonStreaming(messages, depth)
                        .onErrorResume(AiApiException.class, fallbackError -> {
                            log.warn("非流式 fallback 请求失败，状态码: {}，响应: {}",
                                    fallbackError.statusCode, abbreviate(fallbackError.responseBody, 1000));
                            return Flux.just(formatApiError(fallbackError));
                        });
            }
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

    // ========== 流式 SSE 处理 ==========

    /**
     * 发起流式 chat completion 请求，解析 SSE，将 content chunk 逐段 emit。
     */
    private Flux<String> streamChatCompletion(String endpoint, String requestBody, StreamState state) {
        StringBuilder buffer = new StringBuilder();
        return WebClient.create()
                .post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + currentSetting.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .bodyValue(requestBody)
                .exchangeToFlux(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(body -> Flux.error(new AiApiException(response.statusCode().value(), body)));
                    }
                    // 使用 DataBuffer 避免 String decoder 合并 chunk
                    return response.bodyToFlux(DataBuffer.class)
                            .concatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);
                                String chunk = new String(bytes, StandardCharsets.UTF_8);
                                return parseSseChunk(buffer, chunk, state);
                            });
                });
    }

    /**
     * 解析 SSE chunk：维护 buffer，按事件边界切割，解析 data: 行，返回 content 输出。
     */
    private Flux<String> parseSseChunk(StringBuilder buffer, String chunk, StreamState state) {
        buffer.append(chunk);
        List<String> outputs = new ArrayList<>();

        while (true) {
            int end = findSseEventEnd(buffer);
            if (end < 0) break;

            String event = buffer.substring(0, end);
            // 移除已处理部分（含事件结束符）
            int removeTo = end;
            if (buffer.length() > end && buffer.charAt(end) == '\n') removeTo = end + 1;
            if (buffer.length() > end && buffer.charAt(end) == '\r') {
                removeTo = end + 1;
                if (buffer.length() > end + 1 && buffer.charAt(end + 1) == '\n') removeTo = end + 2;
            }
            buffer.delete(0, removeTo);

            for (String line : event.split("\\R")) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isBlank() || "[DONE]".equals(data)) continue;
                try {
                    String content = handleStreamData(data, state);
                    if (!content.isEmpty()) {
                        outputs.add(content);
                    }
                } catch (Exception e) {
                    log.warn("解析 SSE data 失败，跳过: {}", abbreviate(data, 200), e);
                }
            }
        }

        return Flux.fromIterable(outputs);
    }

    /**
     * 在 buffer 中查找 SSE 事件结束位置（\n\n 或 \r\n\r\n）。
     * 返回事件结束符的起始索引，-1 表示未找到完整事件。
     */
    private int findSseEventEnd(StringBuilder buffer) {
        for (int i = 0; i < buffer.length() - 1; i++) {
            if (buffer.charAt(i) == '\n' && buffer.charAt(i + 1) == '\n') {
                return i + 2;
            }
            if (i < buffer.length() - 3
                    && buffer.charAt(i) == '\r' && buffer.charAt(i + 1) == '\n'
                    && buffer.charAt(i + 2) == '\r' && buffer.charAt(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /**
     * 处理一条 data: 中的 JSON，更新 StreamState，返回本段 content。
     */
    private String handleStreamData(String data, StreamState state) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choice = root.path("choices").path(0);
            if (choice.isMissingNode()) return "";

            // finish_reason
            String finishReason = choice.path("finish_reason").asText("");
            if (!finishReason.isBlank() && !"null".equals(finishReason)) {
                state.finishReason = finishReason;
            }

            // index: DeepSeek 等可能返回多条 choice，但一般只处理第一条
            JsonNode delta = choice.path("delta");
            if (delta.isMissingNode()) return "";

            // reasoning_content 只 debug log，不输出给用户
            String reasoning = delta.path("reasoning_content").asText("");
            if (!reasoning.isBlank()) {
                log.debug("reasoning: {}", reasoning.substring(0, Math.min(reasoning.length(), 200)));
            }

            // refusal
            String refusal = delta.path("refusal").asText("");
            if (!refusal.isBlank()) {
                state.refusal = refusal;
            }

            // content — 立即输出
            String content = delta.path("content").asText("");
            if (!content.isEmpty()) {
                state.content.append(content);
                return content;
            }

            // tool_calls — 只累计不输出
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                accumulateToolCalls(toolCalls, state);
            }

            return "";
        } catch (Exception e) {
            log.warn("解析 SSE data JSON 失败: {}", abbreviate(data, 200), e);
            return "";
        }
    }

    // ========== Tool Call 累计 ==========

    /**
     * 按 index 累计 tool_calls 分段的 id / name / arguments。
     */
    private void accumulateToolCalls(JsonNode toolCalls, StreamState state) {
        for (JsonNode tc : toolCalls) {
            int index = tc.path("index").asInt(0);
            ToolCallAccumulator acc = state.toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());
            if (tc.hasNonNull("id")) acc.id = tc.path("id").asText();
            if (tc.hasNonNull("type")) acc.type = tc.path("type").asText();
            JsonNode fn = tc.path("function");
            if (fn.hasNonNull("name")) acc.name = fn.path("name").asText();
            if (fn.has("arguments")) {
                acc.arguments.append(fn.path("arguments").asText(""));
            }
        }
    }

    /**
     * 将流式累计的 tool_calls 转换为标准的非流式 tool_calls JSON 数组节点。
     */
    private ArrayNode buildToolCallsNode(StreamState state) {
        ArrayNode result = objectMapper.createArrayNode();
        for (ToolCallAccumulator acc : state.toolCalls.values()) {
            if (acc.id == null || acc.id.isBlank()) {
                log.warn("跳过缺少 id 的 tool call，name={}", acc.name);
                continue;
            }
            if (acc.name == null || acc.name.isBlank()) {
                log.warn("跳过缺少 name 的 tool call，id={}", acc.id);
                continue;
            }
            ObjectNode tcNode = result.addObject();
            tcNode.put("id", acc.id);
            tcNode.put("type", acc.type != null ? acc.type : "function");
            ObjectNode fnNode = tcNode.putObject("function");
            fnNode.put("name", acc.name);
            fnNode.put("arguments", acc.arguments.toString());
        }
        return result;
    }

    // ========== 工具调用递归 ==========

    /**
     * 流结束后发现有 tool_calls，执行工具并递归继续对话。
     */
    private Flux<String> continueAfterToolCalls(List<ChatMessage> messages, StreamState state, int depth) {
        ArrayNode toolCalls = buildToolCallsNode(state);
        if (toolCalls.size() == 0) {
            return Flux.just("[错误] AI 期望调用工具，但调用格式不完整");
        }

        List<ChatMessage> newMessages = new ArrayList<>(messages);
        // 添加 assistant message（content 可能是空的）
        newMessages.add(new ChatMessage("assistant", state.content.toString(), toolCalls));

        // 执行每个工具并收集结果（在 boundedElastic 上执行，不阻塞 reactor 线程）
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
    }

    // ========== 非流式 fallback ==========

    /**
     * 不支持 stream 的模型回退到非流式调用（代码从原 doChat 抽取）。
     */
    private Flux<String> doChatNonStreaming(List<ChatMessage> messages, int depth) {
        if (depth > MAX_TOOL_DEPTH) {
            return Flux.just("[系统] 工具调用次数过多，请简化指令");
        }

        String endpoint = normalizeEndpoint(currentSetting.getApiEndpoint());
        String toolsJson = toolRegistry.generateToolsJson();

        return WebClient.create()
                .post()
                .uri(URI.create(endpoint + "/chat/completions"))
                .header("Authorization", "Bearer " + currentSetting.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(buildRequestBody(messages, toolsJson, false))
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
                        String reasoning = message.path("reasoning_content").asText();
                        if (!reasoning.isBlank()) {
                            log.debug("reasoning: {}", reasoning.substring(0, Math.min(reasoning.length(), 200)));
                        }
                        JsonNode toolCalls = message.path("tool_calls");

                        if (toolCalls.isArray() && toolCalls.size() > 0) {
                            return Flux.<String>empty()
                                    .concatWith(Flux.defer(() -> {
                                        List<ChatMessage> newMessages = new ArrayList<>(messages);
                                        newMessages.add(new ChatMessage("assistant",
                                                message.path("content").asText(), toolCalls));
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
                                                    return doChatNonStreaming(newMessages, depth + 1);
                                                });
                                    }));
                        }

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
                });
    }

    // ========== 请求体构造 ==========

    /**
     * 构建 API 请求体，支持外部传入的 system message 和 stream 参数。
     */
    private String buildRequestBody(List<ChatMessage> messages, String toolsJson, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", currentSetting.getModel());
        if (stream) {
            root.put("stream", true);
        }

        ArrayNode msgs = root.putArray("messages");

        // 检查是否已有 system 消息（由外部传入）
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> "system".equals(m.role()));

        if (!hasSystemMessage) {
            ObjectNode systemMsg = msgs.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", currentSetting.getSystemPrompt()
                    + "\n\n【硬性规则 - 违者任务失败】\n"
                    + "- 分类/打标签：你的第一句回复必须包含 autoTagArticles 工具调用。不许先说废话，直接调！\n"
                    + "- 查询未发布/草稿文章：必须调用 listArticles，并传 status=draft；不要用发布时间是否为空来猜。\n"
                    + "- 查询已发布文章：必须调用 listArticles，并传 status=published。\n"
                    + "- 展示文章列表时必须使用编号列表，不要使用 Markdown 表格。\n"
                    + "- 创建文章：直接调 createArticle。不反问、不确认、不商量。\n"
                    + "- 永远不说「我先看看」「让我检查」「我发现了一个工具」等废话。直接行动。\n"
                    + "- 回复上限 3 句话。超过就是违规。\n"
                    + "- 当需要展示列表或表格时，必须使用标准 Markdown 表格格式，以 | 开头和结尾，例如：| 序号 | 文章标题 |。分隔行必须完整（如 | --- | --- |）。不要输出零散的 ---、|:、| 作为单独行。");
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

    // ========== 辅助方法 ==========

    private String normalizeEndpoint(String apiEndpoint) {
        String endpoint = apiEndpoint.replaceAll("/+$", "");
        if (!endpoint.endsWith("/v1")) {
            endpoint += "/v1";
        }
        return endpoint;
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
        return abbreviateValue(value, maxLength);
    }

    private static String abbreviateValue(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "...";
    }

    // ========== 数据类 ==========

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

    // ========== 流式内部状态 ==========

    /**
     * 流式响应的累计状态。content 和 toolCalls 在流式过程中逐步填充。
     */
    private static class StreamState {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        private String finishReason;
        private String refusal;

        boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /**
     * 单个 tool call 的分段累计器。OpenAI-compatible streaming 中
     * tool calls 的 id / name / arguments 可能分多条 delta 返回，
     * 必须按 index 拼接。
     */
    private static class ToolCallAccumulator {
        String id;
        String type;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    private static class AiApiException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        private AiApiException(int statusCode, String responseBody) {
            super("AI API returned HTTP " + statusCode + detail(responseBody));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        private static String detail(String responseBody) {
            if (responseBody == null || responseBody.isBlank()) return "";
            return ": " + abbreviateValue(responseBody, 500);
        }
    }
}
