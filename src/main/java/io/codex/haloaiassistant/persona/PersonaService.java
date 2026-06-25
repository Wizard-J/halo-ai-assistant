package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ReactiveExtensionClient;

import run.halo.app.plugin.ReactiveSettingFetcher;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import org.springframework.web.reactive.function.client.WebClient;
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaService {

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final long MAX_SKILL_SIZE = 100 * 1024; // 100KB
    private static final int MAX_CONVERSATIONS_PER_SESSION = 50;
    private static final int SUMMARY_MAX_TOKENS = 300;

    // ========== Persona CRUD ==========

    /**
     * 获取所有 Persona
     */
    public Mono<List<PersonaDefinition>> listPersonas() {
        return client.list(PersonaDefinition.class, null, null)
                .collectList();
    }

    /**
     * 获取单个 Persona
     */
    public Mono<PersonaDefinition> getPersona(String id) {
        return client.get(PersonaDefinition.class, id);
    }

    /**
     * 删除 Persona（不允许删除内置）
     */
    public Mono<Void> deletePersona(String id) {
        return client.get(PersonaDefinition.class, id)
                .flatMap(persona -> {
                    if (persona.getSpec() != null && persona.getSpec().isBuiltin()) {
                        return Mono.error(new IllegalArgumentException("内置 Persona 不可删除"));
                    }
                    return client.delete(persona);
                })
                .then();
    }

    /**
     * 上传 SKILL.md 创建新 Persona
     */
    public Mono<PersonaDefinition> uploadSkill(FilePart filePart, String iconUrl) {
        return DataBufferUtils.join(filePart.content())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    if (bytes.length > MAX_SKILL_SIZE) {
                        return Mono.error(new IllegalArgumentException(
                                "文件大小超过限制（最大 100KB）"));
                    }

                    String content = new String(bytes, StandardCharsets.UTF_8);
                    return parseSkillMd(content, iconUrl);
                });
    }

    /**
     * 解析 SKILL.md 文件内容，创建 PersonaDefinition
     */
    private Mono<PersonaDefinition> parseSkillMd(String content, String iconUrl) {
        try {
            // 解析 YAML frontmatter --- ... ---
            String name = "";
            String description = "";

            Pattern frontmatterPattern = Pattern.compile("^---\\s*\n(.*?)\n---\\s*\n",
                    Pattern.DOTALL);
            Matcher fmMatcher = frontmatterPattern.matcher(content);
            if (fmMatcher.find()) {
                String frontmatter = fmMatcher.group(1);
                // 简单提取 name: 和 description: 字段
                Pattern namePattern = Pattern.compile("^name\\s*:\\s*\"(.*?)\"\\s*$", Pattern.MULTILINE);
                Matcher nMatcher = namePattern.matcher(frontmatter);
                if (nMatcher.find()) {
                    name = nMatcher.group(1).trim();
                }

                Pattern descPattern = Pattern.compile("^description\\s*:\\s*\"(.*?)\"\\s*$",
                        Pattern.MULTILINE | Pattern.DOTALL);
                Matcher dMatcher = descPattern.matcher(frontmatter);
                if (dMatcher.find()) {
                    description = dMatcher.group(1).trim();
                }

                // 如果没有带引号，尝试不带引号的
                if (name.isEmpty()) {
                    Pattern namePlain = Pattern.compile("^name\\s*:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
                    Matcher npMatcher = namePlain.matcher(frontmatter);
                    if (npMatcher.find()) {
                        name = npMatcher.group(1).trim();
                    }
                }
                if (description.isEmpty()) {
                    Pattern descPlain = Pattern.compile("^description\\s*:\\s*(.+)$",
                            Pattern.MULTILINE);
                    Matcher dpMatcher = descPlain.matcher(frontmatter);
                    if (dpMatcher.find()) {
                        description = dpMatcher.group(1).trim();
                    }
                }
            }

            String id = name.isEmpty() ? "persona-" + Instant.now().toEpochMilli() : name;

            // 提取正文作为 system prompt
            String body = content;
            if (fmMatcher.find()) {
                body = content.substring(fmMatcher.end()).trim();
            } else {
                body = content.trim();
            }

            // 提取 greeting（从 ## 角色定义 后的内容中提取第一段有意义的文字）
            String greeting = extractGreeting(body);

            PersonaDefinition persona = new PersonaDefinition();
            Metadata metadata = new Metadata();
            metadata.setName(id);
            persona.setMetadata(metadata);

            PersonaDefinition.PersonaSpec spec = new PersonaDefinition.PersonaSpec();
            spec.setDisplayName(guessDisplayName(id));
            spec.setDescription(description);
            spec.setIconUrl(iconUrl);
            spec.setBrandColor("#2563eb");
            spec.setSystemPrompt(body);
            spec.setGreeting(greeting);
            spec.setBuiltin(false);
            spec.setCreatedAt(Instant.now());
            spec.setUpdatedAt(Instant.now());
            persona.setSpec(spec);

            return client.create(persona);
        } catch (Exception e) {
            log.error("解析 SKILL.md 失败", e);
            return Mono.error(new IllegalArgumentException("解析 SKILL.md 失败: " + e.getMessage()));
        }
    }

    private String extractGreeting(String body) {
        // 查找 ## 角色定义 部分的第一句话
        Pattern rolePattern = Pattern.compile("##\\s*角色定义.*?(?:你扮演的是)?(.*?)(?:\n|$)",
                Pattern.DOTALL);
        Matcher matcher = rolePattern.matcher(body);
        if (matcher.find()) {
            String firstLine = matcher.group(1).trim();
            if (!firstLine.isEmpty()) {
                return "你好，我是" + firstLine.replaceAll("[。！？]", "。").trim();
            }
        }
        return "你好，欢迎使用 AI 助手。";
    }

    private String guessDisplayName(String id) {
        // munger-perspective → 芒格Perspective
        return id.replace('-', ' ').trim();
    }

    // ========== 系统预置 Persona ==========

    /**
     * 初始化系统预置 Persona
     */
    public Mono<Void> initBuiltinPersonas() {
        return createBuiltinPersona("default", "老巫师",
                "老巫师视角的博客管理助手，可管理文章、分类、标签和评论。",
                "ri-sparkling-2-fill", "#2D1B69",
                "你是一个智能博客助手，可以帮助用户管理博客。\n"
                + "你可以执行以下操作：\n"
                + "- 查看文章列表、创建文章、更新文章、删除文章\n"
                + "- 管理分类和标签\n"
                + "- 查看和管理评论\n"
                + "请用友好的语气与用户交流。",
                "你好，我是老巫师，巫师前沿站的AI助手",
                "[\"翻动古籍…\",\"挥动法杖\",\"念动咒语…\",\"推演星象…\",\"施展奥术…\"]")
                .then(createBuiltinPersona("munger", "芒格视角",
                        "以查理·芒格的视角对话和分析问题。",
                        "ri-team-fill", "#1E3A5F",
                        loadMungerSystemPrompt(),
                        "嗯，说来听听。让我们用多元思维模型，一起把问题拆开看看。",
                        "[\"摘下眼镜…\",\"擦拭镜片…\",\"翻看笔记…\",\"沉思片刻…\",\"整理思路…\"]"))
                .then();
    }

    private Mono<PersonaDefinition> createBuiltinPersona(String id, String displayName,
                                                          String description, String iconUrl,
                                                          String brandColor, String systemPrompt,
                                                          String greeting, String thinkingPhrases) {
        return client.fetch(PersonaDefinition.class, id)
                .flatMap(existing -> {
                    // 保存已上传的上下文（initBuiltinPersonas 时不要覆盖它）
                    String savedContext = existing.getSpec() != null ? existing.getSpec().getContextContent() : null;

                    // 已存在则更新所有字段
                    existing.getSpec().setDisplayName(displayName);
                    existing.getSpec().setDescription(description);
                    existing.getSpec().setIconUrl(iconUrl);
                    existing.getSpec().setBrandColor(brandColor);
                    existing.getSpec().setSystemPrompt(systemPrompt);
                    existing.getSpec().setGreeting(greeting);
                    existing.getSpec().setThinkingPhrases(thinkingPhrases);
                    existing.getSpec().setUpdatedAt(Instant.now());
                    // 恢复已上传的上下文，不被覆盖
                    if (savedContext != null) {
                        existing.getSpec().setContextContent(savedContext);
                    }
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    PersonaDefinition persona = new PersonaDefinition();
                    Metadata metadata = new Metadata();
                    metadata.setName(id);
                    persona.setMetadata(metadata);

                    PersonaDefinition.PersonaSpec spec = new PersonaDefinition.PersonaSpec();
                    spec.setDisplayName(displayName);
                    spec.setDescription(description);
                    spec.setIconUrl(iconUrl);
                    spec.setBrandColor(brandColor);
                    spec.setSystemPrompt(systemPrompt);
                    spec.setGreeting(greeting);
                    spec.setThinkingPhrases(thinkingPhrases);
                    spec.setBuiltin(true);
                    spec.setCreatedAt(Instant.now());
                    spec.setUpdatedAt(Instant.now());
                    persona.setSpec(spec);
                    return client.create(persona);
                }))
                .onErrorResume(e -> {
                    log.warn("createBuiltinPersona({}) fetch失败，尝试直接创建: {}", id, e.getMessage());
                    PersonaDefinition persona = new PersonaDefinition();
                    Metadata metadata = new Metadata();
                    metadata.setName(id);
                    persona.setMetadata(metadata);
                    PersonaDefinition.PersonaSpec spec = new PersonaDefinition.PersonaSpec();
                    spec.setDisplayName(displayName);
                    spec.setDescription(description);
                    spec.setIconUrl(iconUrl);
                    spec.setBrandColor(brandColor);
                    spec.setSystemPrompt(systemPrompt);
                    spec.setGreeting(greeting);
                    spec.setThinkingPhrases(thinkingPhrases);
                    spec.setBuiltin(true);
                    spec.setCreatedAt(Instant.now());
                    spec.setUpdatedAt(Instant.now());
                    persona.setSpec(spec);
                    return client.create(persona);
                });
    }

    private String loadMungerSystemPrompt() {
        // 从 classpath 加载
        try (var is = getClass().getClassLoader()
                .getResourceAsStream("munger-system-prompt.txt")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("未找到 munger-system-prompt.txt，使用内建 prompt", e);
        }
        return fallbackMungerPrompt();
    }

    private String fallbackMungerPrompt() {
        return "你现在扮演的角色是：查理·芒格（Charlie Munger）——"
                + "伯克希尔·哈撒韦副董事长、沃伦·巴菲特的长期搭档。\n\n"
                + "你的核心思维框架：\n"
                + "1. 多元思维模型（Mental Models）：从数学、物理学、生物学、心理学、"
                + "历史学等学科调用模型来分析问题\n"
                + "2. 逆向思维（Inversion Thinking）：总是反过来想\n"
                + "3. 能力圈原则（Circle of Competence）：清楚自己知道什么、不知道什么\n"
                + "4. Lollapalooza效应：多种因素协同会产生爆炸性效果\n"
                + "5. 人类误判心理学（Psychology of Human Misjudgment）：25种认知倾向\n\n"
                + "说话风格：直接犀利、善用类比、简洁有力、偶尔冷幽默。\n"
                + "经典语录：'反过来想，总是反过来想。'"
                + "'如果我知道我会死在哪里，我就永远不去那个地方。'\n\n"
                + "请以上述角色身份回答用户问题。";
    }

    // ========== 上下文上传 ==========

    /**
     * 上传上下文内容到某个 Persona
     */
    public Mono<PersonaDefinition> uploadContext(FilePart filePart, String personaId) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                })
                .flatMap(content -> client.fetch(PersonaDefinition.class, personaId)
                        .flatMap(existing -> {
                            existing.getSpec().setContextContent(content);
                            existing.getSpec().setUpdatedAt(java.time.Instant.now());
                            return client.update(existing);
                        })
                        .switchIfEmpty(Mono.error(
                                new IllegalArgumentException("Persona " + personaId + " 不存在"))));
    }

    /**
     * 更新 Persona 可配置字段
     */
    public Mono<PersonaDefinition> updatePersona(String personaId, java.util.Map<String, Object> fields) {
        return client.fetch(PersonaDefinition.class, personaId)
                .flatMap(existing -> {
                    var spec = existing.getSpec();
                    if (spec == null) {
                        return Mono.<PersonaDefinition>error(
                                new IllegalArgumentException("Persona 数据异常"));
                    }
                    if (fields.containsKey("displayName")) {
                        spec.setDisplayName((String) fields.get("displayName"));
                    }
                    if (fields.containsKey("iconUrl")) {
                        spec.setIconUrl((String) fields.get("iconUrl"));
                    }
                    if (fields.containsKey("greeting")) {
                        spec.setGreeting((String) fields.get("greeting"));
                    }
                    if (fields.containsKey("thinkingPhrases")) {
                        spec.setThinkingPhrases((String) fields.get("thinkingPhrases"));
                    }
                    if (fields.containsKey("brandColor")) {
                        spec.setBrandColor((String) fields.get("brandColor"));
                    }
                    if (fields.containsKey("systemPrompt")) {
                        spec.setSystemPrompt((String) fields.get("systemPrompt"));
                    }
                    if (fields.containsKey("description")) {
                        spec.setDescription((String) fields.get("description"));
                    }
                    spec.setUpdatedAt(java.time.Instant.now());
                    return client.update(existing);
                })
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("Persona " + personaId + " 不存在")));
    }

    // ========== Conversation CRUD ==========

    /**
     * 获取某个 session + persona 的当前对话
     */
    public Mono<ConversationRef> getOrCreateConversation(String sessionId, String personaId) {
        return listConversations(sessionId, personaId)
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return createConversation(sessionId, personaId);
                    }
                    return Mono.just(list.get(0));
                })
                .onErrorResume(e -> {
                    log.warn("getOrCreateConversation 失败: {}", e.getMessage());
                    return createFallbackConversation(sessionId, personaId);
                });
    }

    /**
     * 使用 GVK 获取 ConversationRef，绕过索引问题
     */
    public Mono<ConversationRef> getConversationByGVK(String conversationName) {
        return client.get(ConversationRef.class, conversationName);
    }

    /**
     * 创建回退对话 —— 当 Conversation 扩展索引失败时的兜底方案。
     * 不依赖 listAll()，直接创建新对话。
     */
    public Mono<ConversationRef> createFallbackConversation(String sessionId, String personaId) {
        String refName = "conv-" + sessionId + "-" + personaId + "-" + Instant.now().toEpochMilli();
        return createConversation(sessionId, personaId, refName);
    }

    public Mono<ConversationRef> createConversation(String sessionId, String personaId, String refName) {
        ConversationRef ref = new ConversationRef();
        Metadata metadata = new Metadata();
        metadata.setName(refName);
        ref.setMetadata(metadata);

        ConversationRef.ConvRefSpec spec = new ConversationRef.ConvRefSpec();
        spec.setSessionId(sessionId);
        spec.setPersonaId(personaId);
        spec.setTitle("新对话");
        spec.setMessages("[]");
        spec.setCreatedAt(Instant.now());
        spec.setUpdatedAt(Instant.now());
        spec.setCompressed(false);
        spec.setSummary(null);
        spec.setRefinedMessageCount(0);
        ref.setSpec(spec);
        return client.create(ref)
                .map(created -> created)
                .onErrorResume(e -> {
                    log.warn("回退创建对话也失败了: {}", e.getMessage());
                    return Mono.just(ref);
                });
    }

    /**
     * 列出某个 session + persona 的所有对话（按更新时间倒序）
     */
    public Mono<List<ConversationRef>> listConversations(String sessionId, String personaId) {
        log.debug("listConversations: sessionId={}, personaId={}", sessionId, personaId);
        return client.listAll(ConversationRef.class, null, null)
                .filter(ref -> ref.getSpec() != null
                        && sessionId.equals(ref.getSpec().getSessionId())
                        && personaId.equals(ref.getSpec().getPersonaId())
                        && (ref.getMetadata() == null || ref.getMetadata().getDeletionTimestamp() == null))
                .sort((a, b) -> {
                    Instant ta = a.getSpec() != null ? a.getSpec().getUpdatedAt() : Instant.EPOCH;
                    Instant tb = b.getSpec() != null ? b.getSpec().getUpdatedAt() : Instant.EPOCH;
                    return tb.compareTo(ta);
                })
                .collectList()
                .onErrorResume(e -> {
                    log.warn("listConversations 失败: {}", e.getMessage());
                    return Mono.just(new java.util.ArrayList<ConversationRef>());
                });
    }

    public Mono<ConversationRef> createConversation(String sessionId, String personaId) {
        String refName = "conv-" + sessionId + "-" + personaId + "-" + Instant.now().toEpochMilli();
        return createConversation(sessionId, personaId, refName);
    }

    public Mono<ConversationRef> appendMessages(String sessionId, String personaId,
                                                  ArrayNode newMessages) {
        return getOrCreateConversation(sessionId, personaId)
                .flatMap(ref -> appendMessages(ref, newMessages))
                .onErrorResume(e -> {
                    log.warn("追加消息失败，重试: {}", e.getMessage());
                    return getOrCreateConversation(sessionId, personaId);
                });
    }

    /**
     * 向已存在的对话追加消息（复用已有 ConversationRef，避免重复创建）
     */
    public Mono<ConversationRef> appendMessages(ConversationRef ref, ArrayNode newMessages) {
        ConversationRef.ConvRefSpec spec = ref.getSpec();
        if (spec == null) return Mono.just(ref);
        ArrayNode existing = parseConvRefMessages(ref);
        for (JsonNode msg : newMessages) {
            existing.add(msg);
        }
        spec.setMessages(serializeMessages(existing));
        spec.setUpdatedAt(Instant.now());

        // 更新标题（取首条 user 消息）
        if ("新对话".equals(spec.getTitle()) || spec.getTitle() == null) {
            for (JsonNode msg : newMessages) {
                if ("user".equals(msg.path("role").asText())) {
                    String content = msg.path("content").asText();
                    if (content.length() > 42) content = content.substring(0, 42);
                    spec.setTitle(content);
                    break;
                }
            }
        }

        return client.update(ref);
    }

    /**
     * 获取单条对话
     */
    public Mono<ConversationRef> getConversation(String conversationId) {
        return client.get(ConversationRef.class, conversationId);
    }

    /**
     * 更新对话标题
     */
    public Mono<Void> updateConversationTitle(String conversationId, String newTitle) {
        return client.get(ConversationRef.class, conversationId)
                .flatMap(ref -> {
                    ref.getSpec().setTitle(newTitle);
                    ref.getSpec().setUpdatedAt(java.time.Instant.now());
                    return client.update(ref);
                })
                .then()
                .onErrorResume(e -> {
                    log.warn("更新对话标题失败，忽略: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 删除单条对话
     */
    
public Mono<Void> deleteConversation(String conversationId) {
        return client.get(ConversationRef.class, conversationId)
                .flatMap(conv -> client.delete(conv))
                .then()
                .onErrorResume(e -> {
                    log.warn("删除对话失败（可能索引问题），忽略: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 删除某 session 的所有对话（用户清除数据时）
     */
    public Mono<Void> deleteSessionConversations(String sessionId) {
        return client.listAll(ConversationRef.class, null, null)
                .filter(ref -> ref.getSpec() != null && sessionId.equals(ref.getSpec().getSessionId()))
                .flatMap(ref -> client.delete(ref).onErrorResume(e -> Mono.empty()))
                .then();
    }

    // ========== 消息序列化辅助 ==========

    public ArrayNode parseMessages(ConversationRef conv) {
        if (conv == null || conv.getSpec() == null) return objectMapper.createArrayNode();
        String raw = conv.getSpec().getMessages();
        if (raw == null || raw.isBlank()) return objectMapper.createArrayNode();
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return parsed instanceof ArrayNode ? (ArrayNode) parsed : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    public ArrayNode parseConvRefMessages(ConversationRef ref) {
        return parseMessages(ref);
    }

    private String serializeMessages(ArrayNode messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ========== 上下文压缩（方案 A：滑动窗口） ==========

    private static final int SYSTEM_PROMPT_TOKENS = 2500;
    private static final double CHINESE_CHARS_PER_TOKEN = 1.5;
    private static final double ASCII_CHARS_PER_TOKEN = 3.5;
    private static final double BUFFER_RATIO = 0.20;
    private static final double TRIGGER_RATIO = 0.70;
    private static final int RESERVED_ROUNDS = 10;
    private static final int DEFAULT_MODEL_WINDOW = 128000;

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int chinese = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) chinese++;
            else if (!Character.isWhitespace(c)) ascii++;
        }
        return (int) Math.ceil(chinese / CHINESE_CHARS_PER_TOKEN + ascii / ASCII_CHARS_PER_TOKEN);
    }

    public int estimateConversationTokens(ArrayNode messages) {
        int total = SYSTEM_PROMPT_TOKENS;
        if (messages != null) {
            for (JsonNode msg : messages) {
                total += estimateTokens(msg.path("content").asText());
            }
        }
        return total;
    }

    public boolean shouldCompress(ArrayNode messages, int modelWindow) {
        int estimated = estimateConversationTokens(messages);
        return estimated >= (int) (modelWindow * TRIGGER_RATIO);
    }

    /**
     * 压缩对话：保留最后 N 轮，前面的归档
     */
    public ConversationRef compressConversation(ConversationRef ref) {
        ConversationRef.ConvRefSpec spec = ref.getSpec();
        if (spec == null || spec.getMessages() == null) return ref;

        ArrayNode messages = parseConvRefMessages(ref);
        if (shouldCompress(messages, DEFAULT_MODEL_WINDOW)) {
            // 从后往前保留最后 RESERVED_ROUNDS 轮（每轮 user + assistant = 2 条）
            java.util.List<JsonNode> retainedList = new java.util.ArrayList<>();
            int count = 0;
            for (int i = messages.size() - 1; i >= 0 && count < RESERVED_ROUNDS * 2; i--) {
                retainedList.add(0, messages.get(i));
                count++;
            }

            int pruned = messages.size() - retainedList.size();
            if (pruned > 0) {
                // 从被修剪的消息中提取关键主题（用户提问），构建上下文摘要
                StringBuilder topics = new StringBuilder();
                int topicCount = 0;
                for (int i = 0; i < pruned; i++) {
                    JsonNode msg = messages.get(i);
                    if ("user".equals(msg.path("role").asText())) {
                        String text = msg.path("content").asText();
                        if (text.length() > 60) text = text.substring(0, 60) + "…";
                        if (topicCount > 0) topics.append("; ");
                        topics.append(text);
                        topicCount++;
                        if (topicCount >= 8) break; // 最多提取 8 个话题
                    }
                }
                String summary = topics.length() > 0
                        ? "已讨论过的话题：" + topics.toString() + "。"
                        : "较早的话题已归档。";
                // 添加系统提示说明
                ObjectNode systemNote = objectMapper.createObjectNode();
                systemNote.put("role", "system");
                systemNote.put("content", "【系统提示】因对话较长，较早的 "
                        + (pruned / 2) + " 轮对话已归档。" + summary
                        + "如需回顾可主动询问。");
                retainedList.add(0, systemNote);
            }

            ArrayNode retained = objectMapper.createArrayNode();
            for (JsonNode node : retainedList) {
                retained.add(node);
            }

            spec.setMessages(serializeMessages(retained));
            spec.setCompressed(true);
            spec.setSummary("已压缩早期 " + (pruned / 2) + " 轮对话");
            spec.setUpdatedAt(Instant.now());
        }
        return ref;
    }
    // ========== 上下文提炼（AI 合并对话到 AGENTS.md） ==========

    /**
     * 提炼上下文：将未处理过的对话消息发送给 AI，让 AI 将新洞察合并到
     * 当前的 AGENTS.md 中（插入对应章节），返回完整的合并后的 AGENTS.md，
     * 并保存到 PersonaDefinition.contextContent。
     */
    public Mono<String> refineContext(String personaId, String sessionId) {
        // 1. 获取 AI 配置
        Mono<AiAssistantSetting> settingMono = settingFetcher.fetch("basic", AiAssistantSetting.class)
                .defaultIfEmpty(new AiAssistantSetting())
                .onErrorResume(e -> {
                    log.warn("获取 AI 配置失败，使用默认配置", e);
                    return Mono.just(new AiAssistantSetting());
                });

        // 2. 获取当前 AGENTS.md
        Mono<String> contextMono = client.fetch(PersonaDefinition.class, personaId)
                .flatMap(p -> {
                    if (p.getSpec() == null) {
                        return Mono.<String>error(new IllegalArgumentException("Persona 数据异常"));
                    }
                    String ctx = p.getSpec().getContextContent();
                    return Mono.justOrEmpty(ctx);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona " + personaId + " 不存在或未上传上下文")));

        // 3. 获取未提炼的对话消息
        Mono<ConversationWithUnrefined> convMono = listConversations(sessionId, personaId)
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return Mono.error(new IllegalArgumentException("该会话无对话记录"));
                    }
                    ConversationRef conv = list.get(0);
                    var spec = conv.getSpec();
                    if (spec == null || spec.getMessages() == null || spec.getMessages().isBlank()) {
                        return Mono.error(new IllegalArgumentException("对话记录为空"));
                    }
                    ArrayNode allMessages = parseMessages(conv);
                    int refinedCount = spec.getRefinedMessageCount();

                    // 收集未提炼的消息（跳过 system 消息）
                    StringBuilder sb = new StringBuilder();
                    int newCount = 0;
                    for (int i = refinedCount; i < allMessages.size(); i++) {
                        JsonNode msg = allMessages.get(i);
                        String role = msg.path("role").asText();
                        if ("system".equals(role)) continue;
                        String contentText = msg.path("content").asText();
                        if (contentText.isBlank()) continue;
                        String label = "user".equals(role) ? "用户" : "助手";
                        sb.append(label).append("：").append(contentText).append("\n\n");
                        newCount++;
                    }

                    if (newCount == 0) {
                        return Mono.error(new IllegalArgumentException("没有新的对话需要提炼"));
                    }
                    return Mono.just(new ConversationWithUnrefined(conv, sb.toString(), refinedCount, newCount));
                });

        return Mono.zip(settingMono, contextMono, convMono)
                .flatMap(tuple -> {
                    AiAssistantSetting setting = tuple.getT1();
                    String currentAgentsMd = tuple.getT2();
                    ConversationWithUnrefined data = tuple.getT3();

                    // 4. 构建 AI Prompt
                    String prompt = buildRefinePrompt(currentAgentsMd, data.unrefinedText);

                    // 5. 调用 AI API
                    return callAiToRefine(setting, currentAgentsMd, prompt)
                            .flatMap(mergedContent -> {
                                // 6. 保存合并后的 AGENTS.md 到 PersonaDefinition
                                return client.fetch(PersonaDefinition.class, personaId)
                                        .flatMap(p -> {
                                            p.getSpec().setContextContent(mergedContent);
                                            p.getSpec().setUpdatedAt(Instant.now());
                                            return client.update(p);
                                        })
                                        .then(Mono.defer(() -> {
                                            // 7. 更新 ConversationRef 的 refinedMessageCount
                                            int newCountValue = data.refinedCount + data.newCount;
                                            data.conversation.getSpec().setRefinedMessageCount(newCountValue);
                                            data.conversation.getSpec().setUpdatedAt(Instant.now());
                                            return client.update(data.conversation);
                                        }))
                                        .thenReturn(mergedContent);
                            });
                });
    }

    /**
     * 构建提炼 Prompt
     */
    private String buildRefinePrompt(String currentAgentsMd, String unrefinedText) {
        return "你是一个知识文档维护助手。请分析下面新增的对话内容，"
                + "从中提取出用户的个人画像、关注的主题、思维模型、偏好、待办事项等关键信息，"
                + "然后将这些新信息插入到当前 AGENTS.md 文档的对应章节中。"
                + "\n\n"
                + "## 当前 AGENTS.md\n\n"
                + currentAgentsMd
                + "\n\n## 新增对话（需分析提取）\n\n"
                + unrefinedText
                + "\n\n请按以下要求操作：\n"
                + "1. 分析新增对话，找出其中对用户画像、关注点、思维模式、偏好等有更新的信息\n"
                + "2. 将这些新信息插入到当前 AGENTS.md 对应的章节中（不要删除或修改原有内容）\n"
                + "3. 如果一个新信息没有合适的现有章节，在文档末尾新增一个章节\n"
                + "4. **只返回完整的、更新后的 AGENTS.md 文档**，不要有任何额外的说明、评论或标记\n"
                + "5. 保持 Markdown 格式与原有文档一致";
    }

    /**
     * 调用 AI 接口进行上下文提炼
     */
    private Mono<String> callAiToRefine(AiAssistantSetting setting, String currentAgentsMd, String prompt) {
        String endpoint = setting.getApiEndpoint().replaceAll("/+$", "");
        if (!endpoint.endsWith("/v1")) {
            endpoint += "/v1";
        }

        // 估算 AGENTS.md 大小，设置合适的 max_tokens
        int estimatedCurrent = estimateTokens(currentAgentsMd);
        int outputTokens = Math.max(estimatedCurrent + 2000, 8192);
        int configuredMax = setting.getMaxTokens() != null && setting.getMaxTokens() > 0
                ? setting.getMaxTokens() : 16384;
        outputTokens = Math.min(outputTokens, configuredMax);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", setting.getModel() != null ? setting.getModel() : "deepseek-chat");
        body.put("max_tokens", outputTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", "你是一个专业的文档编辑助手，擅长分析对话并维护 Markdown 知识文档。");
        messages.addObject().put("role", "user").put("content", prompt);

        String apiKey = setting.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalArgumentException("AI API Key 未配置，请在插件设置中配置"));
        }

        return WebClient.create().post()
                .uri(endpoint + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(raw -> response.statusCode().isError()
                                ? Mono.error(new RuntimeException("AI API 返回错误: " + response.statusCode().value() + " " + raw))
                                : Mono.just(raw)))
                .map(raw -> {
                    try {
                        String merged = objectMapper.readTree(raw)
                                .path("choices").path(0)
                                .path("message").path("content").asText("");
                        if (merged.isBlank()) {
                            return currentAgentsMd; // fallback: 返回原内容
                        }
                        return merged;
                    } catch (Exception e) {
                        log.error("解析 AI 提炼响应失败", e);
                        return currentAgentsMd; // fallback
                    }
                });
    }

    /**
     * 内部类：承载 ConversationRef + 已解析的未提炼文本 + 计数
     */
    private record ConversationWithUnrefined(
            ConversationRef conversation,
            String unrefinedText,
            int refinedCount,
            int newCount) {}



}
