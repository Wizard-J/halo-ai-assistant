package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.JsonExtension;
import reactor.test.StepVerifier;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class PersonaServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private run.halo.app.plugin.ReactiveSettingFetcher settingFetcher;

    private PersonaService personaService;

    @BeforeEach
    void setUp() {
        personaService = new PersonaService(client, settingFetcher);
    }

    // ========== createConversation ==========

    @Test
    @DisplayName("createConversation → 返回服务端创建的 ref（含 version）")
    void createConversationReturnsCreatedRef() {
        // Arrange: client.create 返回带 version 的 ref
        ConversationRef serverRef = new ConversationRef();
        Metadata meta = new Metadata();
        meta.setName("test-conv-123");
        meta.setVersion(1L); // Halo 自动设置 version
        serverRef.setMetadata(meta);
        ConversationRef.ConvRefSpec spec = new ConversationRef.ConvRefSpec();
        spec.setSessionId("session1");
        spec.setPersonaId("default");
        spec.setTitle("新对话");
        spec.setMessages("[]");
        spec.setCreatedAt(Instant.now());
        spec.setUpdatedAt(Instant.now());
        spec.setRefinedMessageCount(0);
        serverRef.setSpec(spec);

        when(client.create(any(JsonExtension.class))).thenReturn(Mono.just(jsonFromRef(serverRef)));

        // Act: 通过服务方法间接测试（getOrCreateConversation 内部调用 createConversation）
        when(client.listAll(eq(ConversationRef.class), isNull(), isNull()))
                .thenReturn(Flux.empty()); // 无已有对话，触发创建

        Mono<ConversationRef> result = personaService.getOrCreateConversation("session1", "default");

        // Assert: 返回的 ref 有 version
        StepVerifier.create(result)
                .assertNext(ref -> {
                    assertNotNull(ref.getMetadata(), "metadata 不应为空");
                    assertNotNull(ref.getMetadata().getVersion(), "version 不应为空");
                    assertEquals(1L, ref.getMetadata().getVersion());
                    assertEquals("session1", ref.getSpec().getSessionId());
                    assertEquals("default", ref.getSpec().getPersonaId());
                    assertEquals("[]", ref.getSpec().getMessages());
                    assertEquals("新对话", ref.getSpec().getTitle());
                })
                .verifyComplete();

        verify(client, times(1)).create(any(JsonExtension.class));
    }

    // ========== appendMessages(ConversationRef, ArrayNode) ==========

    @Test
    @DisplayName("appendMessages(ConversationRef, ArrayNode) → 追加消息并更新 title")
    void appendMessagesToExistingRef() {
        // Arrange: 已有 ref 带一条历史消息
        ConversationRef ref = createRefWithMessages("session1", "default",
                "[{\"role\":\"user\",\"content\":\"上一轮消息\"}]");
        ref.getMetadata().setVersion(1L);

        ArrayNode newMessages = objectMapper.createArrayNode();
        ObjectNode userMsg = newMessages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", "今天天气怎么样");

        when(client.update(any(JsonExtension.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act
        Mono<ConversationRef> result = personaService.appendMessages(ref, newMessages);

        // Assert
        StepVerifier.create(result)
                .assertNext(updated -> {
                    assertEquals("session1", updated.getSpec().getSessionId());
                    // 现在应该有 2 条消息
                    String msgs = updated.getSpec().getMessages();
                    assertTrue(msgs.contains("上一轮消息"), "应保留原有消息");
                    assertTrue(msgs.contains("今天天气怎么样"), "应包含新消息");
                    // title 应更新为首条 user 消息
                    assertEquals("今天天气怎么样", updated.getSpec().getTitle());
                })
                .verifyComplete();

        verify(client, times(1)).update(any(JsonExtension.class));
    }

    @Test
    @DisplayName("appendMessages(ConversationRef, ArrayNode) → 不覆盖已有 title")
    void appendMessagesPreservesExistingTitle() {
        // Arrange: 已有 title 的 ref
        ConversationRef ref = createRefWithMessages("session1", "default", "[]");
        ref.getSpec().setTitle("已有标题");
        ref.getMetadata().setVersion(1L);

        ArrayNode newMessages = objectMapper.createArrayNode();
        ObjectNode userMsg = newMessages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", "新消息内容");

        when(client.update(any(JsonExtension.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act
        StepVerifier.create(personaService.appendMessages(ref, newMessages))
                .assertNext(updated -> {
                    assertEquals("已有标题", updated.getSpec().getTitle(), "已有 title 不应被覆盖");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("appendMessages(ConversationRef, ArrayNode) → null spec 不崩溃")
    void appendMessagesNullSpec() {
        ConversationRef ref = new ConversationRef();
        ref.setMetadata(new Metadata());
        ref.getMetadata().setName("bad-ref");

        ArrayNode newMessages = objectMapper.createArrayNode();
        newMessages.addObject().put("role", "user").put("content", "hi");

        // 不应该抛异常
        StepVerifier.create(personaService.appendMessages(ref, newMessages))
                .assertNext(r -> assertSame(ref, r))
                .verifyComplete();
    }

    // ========== appendMessages(String, String, ArrayNode) ==========

    @Test
    @DisplayName("appendMessages(sessionId, personaId) → 查找已有对话并追加")
    void appendMessagesBySessionAndPersona() {
        // Arrange: 已有对话
        ConversationRef existingRef = createRefWithMessages("session1", "default", "[]");
        existingRef.getMetadata().setName("existing-conv");
        existingRef.getMetadata().setVersion(1L);

        when(client.listAll(eq(ConversationRef.class), isNull(), isNull()))
                .thenReturn(Flux.just(existingRef));
        when(client.update(any(JsonExtension.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        ArrayNode newMessages = objectMapper.createArrayNode();
        newMessages.addObject().put("role", "user").put("content", "你好");

        // Act
        StepVerifier.create(personaService.appendMessages("session1", "default", newMessages))
                .assertNext(ref -> {
                    assertTrue(ref.getSpec().getMessages().contains("你好"), "应包含新消息");
                })
                .verifyComplete();

        verify(client, times(1)).update(any(JsonExtension.class));
    }

    // ========== compressConversation ==========

    @Test
    @DisplayName("compressConversation → 短对话不压缩")
    void compressConversationShort() {
        ConversationRef ref = createRefWithMessages("s1", "default",
                "[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"hello\"}]");
        ref.getSpec().setCompressed(false);

        ConversationRef result = personaService.compressConversation(ref);

        assertFalse(result.getSpec().isCompressed(), "短对话不应被压缩");
        assertEquals(2, parseMsgCount(result), "消息数量不变");
    }

    @Test
    @DisplayName("compressConversation → 长对话执行压缩（滑动窗口）")
    void compressConversationLong() {
        // 构造一个较长对话（超过 70% 触发阈值 ~89600 tokens）
        StringBuilder msgs = new StringBuilder("[");
        String longText = "这是一个很长的测试消息用于验证压缩功能是否正常工作。我们来测试当对话非常长的时候是否能够自动触发压缩归档机制。"
                .repeat(120);
        for (int i = 0; i < 55; i++) {
            if (i > 0) msgs.append(",");
            String role = i % 2 == 0 ? "user" : "assistant";
            msgs.append("{\"role\":\"").append(role)
                .append("\",\"content\":\"").append(longText).append("\"}");
        }
        msgs.append("]");

        ConversationRef ref = createRefWithMessages("s1", "default", msgs.toString());
        ref.getSpec().setCompressed(false);
        ref.getMetadata().setVersion(1L);

        ConversationRef result = personaService.compressConversation(ref);

        assertTrue(result.getSpec().isCompressed(), "应标记为已压缩");
        assertTrue(parseMsgCount(result) < 55, "压缩后消息数应减少");
        assertTrue(parseMsgCount(result) > 0, "压缩后仍应有消息");
    }

    // ========== listConversations ==========

    @Test
    @DisplayName("listConversations → 按 sessionId + personaId 过滤")
    void listConversationsFilterBySessionAndPersona() {
        ConversationRef ref1 = createRefWithMessages("s1", "default", "[]");
        ref1.getMetadata().setName("conv-1");
        ref1.getSpec().setUpdatedAt(Instant.now());

        ConversationRef ref2 = createRefWithMessages("s2", "default", "[]"); // 不同 session
        ref2.getMetadata().setName("conv-2");

        ConversationRef ref3 = createRefWithMessages("s1", "munger", "[]"); // 不同 persona
        ref3.getMetadata().setName("conv-3");

        when(client.listAll(eq(ConversationRef.class), isNull(), isNull()))
                .thenReturn(Flux.just(ref1, ref2, ref3));

        StepVerifier.create(personaService.listConversations("s1", "default"))
                .assertNext(list -> {
                    assertEquals(1, list.size(), "应只返回 s1 + default 的对话");
                    assertEquals("conv-1", list.get(0).getMetadata().getName());
                })
                .verifyComplete();
    }

    // ========== deleteConversation ==========

    @Test
    @DisplayName("deleteConversation → 删除指定 ConvRef")
    void deleteConversationById() {
        JsonExtension ref = jsonConvRef("to-delete");

        when(client.getJsonExtension(any(GroupVersionKind.class), eq("to-delete")))
                .thenReturn(Mono.just(ref));
        when(client.delete(ref)).thenReturn(Mono.just(ref));

        StepVerifier.create(personaService.deleteConversation("to-delete"))
                .verifyComplete();

        verify(client, times(1)).delete(ref);
    }

    @Test
    @DisplayName("deleteConversation → 不存在的对话静默处理")
    void deleteConversationNotFound() {
        when(client.getJsonExtension(any(GroupVersionKind.class), eq("not-exist")))
                .thenReturn(Mono.empty());

        StepVerifier.create(personaService.deleteConversation("not-exist"))
                .verifyComplete(); // 不应抛异常

        verify(client, never()).delete(any(JsonExtension.class));
    }

    // ========== 辅助方法 ==========

    private ConversationRef createRefWithMessages(String sessionId, String personaId, String messagesJson) {
        ConversationRef ref = new ConversationRef();
        Metadata meta = new Metadata();
        meta.setName("conv-" + sessionId + "-" + personaId + "-" + Instant.now().toEpochMilli());
        ref.setMetadata(meta);

        ConversationRef.ConvRefSpec spec = new ConversationRef.ConvRefSpec();
        spec.setSessionId(sessionId);
        spec.setPersonaId(personaId);
        spec.setTitle("新对话");
        spec.setMessages(messagesJson);
        spec.setCreatedAt(Instant.now());
        spec.setUpdatedAt(Instant.now());
        spec.setCompressed(false);
        spec.setRefinedMessageCount(0);
        ref.setSpec(spec);
        return ref;
    }

    private JsonExtension jsonConvRef(String name) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("apiVersion", "ai-assistant.plugin.halo.run/v1alpha1");
        node.put("kind", "ConvRef");
        ObjectNode metadata = node.putObject("metadata");
        metadata.put("name", name);
        metadata.put("version", 1L);
        return new JsonExtension(objectMapper, node);
    }

    private JsonExtension jsonFromRef(ConversationRef ref) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("apiVersion", "ai-assistant.plugin.halo.run/v1alpha1");
        node.put("kind", "ConvRef");
        ObjectNode metadata = node.putObject("metadata");
        metadata.put("name", ref.getMetadata().getName());
        if (ref.getMetadata().getVersion() != null) {
            metadata.put("version", ref.getMetadata().getVersion());
        }
        ObjectNode specNode = node.putObject("spec");
        specNode.put("sessionId", ref.getSpec().getSessionId());
        specNode.put("personaId", ref.getSpec().getPersonaId());
        specNode.put("title", ref.getSpec().getTitle());
        specNode.put("messages", ref.getSpec().getMessages());
        if (ref.getSpec().getCreatedAt() != null) {
            specNode.put("createdAt", ref.getSpec().getCreatedAt().toString());
        }
        if (ref.getSpec().getUpdatedAt() != null) {
            specNode.put("updatedAt", ref.getSpec().getUpdatedAt().toString());
        }
        specNode.put("compressed", ref.getSpec().isCompressed());
        specNode.put("refinedMessageCount", ref.getSpec().getRefinedMessageCount());
        return new JsonExtension(objectMapper, node);
    }

    private int parseMsgCount(ConversationRef ref) {
        try {
            return objectMapper.readTree(ref.getSpec().getMessages()).size();
        } catch (Exception e) {
            return 0;
        }
    }
}
