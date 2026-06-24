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
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaService {

    private final ReactiveExtensionClient client;
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
                "🤖", "#2563eb",
                "你是一个智能博客助手，可以帮助用户管理博客。\n"
                + "你可以执行以下操作：\n"
                + "- 查看文章列表、创建文章、更新文章、删除文章\n"
                + "- 管理分类和标签\n"
                + "- 查看和管理评论\n"
                + "请用友好的语气与用户交流。",
                "你好，我是老巫师，巫师前沿站的AI助手")
                .then(createBuiltinPersona("munger", "芒格视角",
                        "以查理·芒格的视角对话和分析问题。",
                        null, "#1E3A5F",
                        loadMungerSystemPrompt(),
                        "你好，我是老芒格，有什么需要分析的？"))
                .then();
    }

    private Mono<PersonaDefinition> createBuiltinPersona(String id, String displayName,
                                                          String description, String iconUrl,
                                                          String brandColor, String systemPrompt,
                                                          String greeting) {
        return client.fetch(PersonaDefinition.class, id)
                .flatMap(existing -> {
                    // 已存在则更新
                    existing.getSpec().setSystemPrompt(systemPrompt);
                    existing.getSpec().setUpdatedAt(Instant.now());
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
                    spec.setBuiltin(true);
                    spec.setCreatedAt(Instant.now());
                    spec.setUpdatedAt(Instant.now());
                    persona.setSpec(spec);
                    return client.create(persona);
                }));
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

    // ========== Conversation CRUD ==========

    /**
     * 获取某个 session + persona 的当前对话
     */
    public Mono<Conversation> getOrCreateConversation(String sessionId, String personaId) {
        return listConversations(sessionId, personaId)
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return createConversation(sessionId, personaId);
                    }
                    return Mono.just(list.get(0));
                });
    }

    /**
     * 列出某个 session + persona 的所有对话（按更新时间倒序）
     */
    public Mono<List<Conversation>> listConversations(String sessionId, String personaId) {
        return client.list(Conversation.class,
                        conv -> conv.getSpec() != null
                                && sessionId.equals(conv.getSpec().getSessionId())
                                && personaId.equals(conv.getSpec().getPersonaId()),
                        (a, b) -> {
                            Instant ta = a.getSpec() != null ? a.getSpec().getUpdatedAt() : Instant.EPOCH;
                            Instant tb = b.getSpec() != null ? b.getSpec().getUpdatedAt() : Instant.EPOCH;
                            return tb.compareTo(ta);
                        })
                .collectList();
    }

    private Mono<Conversation> createConversation(String sessionId, String personaId) {
        // 检查该 session 的对话数量限制
        return client.list(Conversation.class,
                        conv -> conv.getSpec() != null && sessionId.equals(conv.getSpec().getSessionId()),
                        null)
                .count()
                .flatMap(count -> {
                    if (count >= MAX_CONVERSATIONS_PER_SESSION) {
                        return Mono.error(new IllegalArgumentException(
                                "对话数量超过限制（最多 " + MAX_CONVERSATIONS_PER_SESSION + " 条）"));
                    }
                    Conversation conv = new Conversation();
                    Metadata metadata = new Metadata();
                    metadata.setName("conv-" + sessionId + "-" + personaId + "-" + Instant.now().toEpochMilli());
                    conv.setMetadata(metadata);

                    Conversation.ConversationSpec spec = new Conversation.ConversationSpec();
                    spec.setSessionId(sessionId);
                    spec.setPersonaId(personaId);
                    spec.setTitle("新对话");
                    spec.setMessages(objectMapper.createArrayNode());
                    spec.setCreatedAt(Instant.now());
                    spec.setUpdatedAt(Instant.now());
                    spec.setCompressed(false);
                    spec.setSummary(null);
                    conv.setSpec(spec);
                    return client.create(conv);
                });
    }

    /**
     * 向对话追加消息
     */
    public Mono<Conversation> appendMessages(String sessionId, String personaId,
                                              ArrayNode newMessages) {
        return getOrCreateConversation(sessionId, personaId)
                .flatMap(conv -> {
                    Conversation.ConversationSpec spec = conv.getSpec();
                    ArrayNode existing = spec.getMessages();
                    if (existing == null) {
                        existing = objectMapper.createArrayNode();
                    }
                    for (JsonNode msg : newMessages) {
                        existing.add(msg);
                    }
                    spec.setMessages(existing);
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

                    return client.update(conv);
                });
    }

    /**
     * 删除单条对话
     */
    public Mono<Void> deleteConversation(String conversationId) {
        return client.get(Conversation.class, conversationId)
                .flatMap(client::delete)
                .then();
    }

    /**
     * 删除某 session 的所有对话（用户清除数据时）
     */
    public Mono<Void> deleteSessionConversations(String sessionId) {
        return client.list(Conversation.class,
                        conv -> conv.getSpec() != null && sessionId.equals(conv.getSpec().getSessionId()),
                        null)
                .flatMap(client::delete)
                .then();
    }

    // ========== 上下文压缩（方案 A：滑动窗口） ==========

    private static final int SYSTEM_PROMPT_TOKENS = 2500;
    private static final double CHINESE_CHARS_PER_TOKEN = 1.5;
    private static final double ASCII_CHARS_PER_TOKEN = 3.5;
    private static final double BUFFER_RATIO = 0.20;
    private static final double TRIGGER_RATIO = 0.70;
    private static final int RESERVED_ROUNDS = 3;
    private static final int DEFAULT_MODEL_WINDOW = 64000;

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
    public Conversation compressConversation(Conversation conv) {
        Conversation.ConversationSpec spec = conv.getSpec();
        if (spec == null || spec.getMessages() == null) return conv;

        ArrayNode messages = spec.getMessages();
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
                // 添加系统提示说明
                ObjectNode systemNote = objectMapper.createObjectNode();
                systemNote.put("role", "system");
                systemNote.put("content", "【系统提示】因对话较长，较早的 "
                        + (pruned / 2) + " 轮对话已归档。如需回顾可主动询问。");
                retainedList.add(0, systemNote);
            }

            ArrayNode retained = objectMapper.createArrayNode();
            for (JsonNode node : retainedList) {
                retained.add(node);
            }

            spec.setMessages(retained);
            spec.setCompressed(true);
            spec.setSummary("已压缩早期 " + (pruned / 2) + " 轮对话");
            spec.setUpdatedAt(Instant.now());
        }
        return conv;
    }
}
