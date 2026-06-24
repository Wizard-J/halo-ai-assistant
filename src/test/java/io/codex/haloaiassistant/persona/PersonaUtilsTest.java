package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯函数单元测试 — Token 估算、压缩判断（无 Halo API 依赖）。
 */
public class PersonaUtilsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ========== estimateTokens ==========

    @Test
    @DisplayName("estimateTokens → 空字符串/null 返回 0")
    public void estimateTokensEmpty() {
        assertEquals(0, PersonaUtils.estimateTokens(""));
        assertEquals(0, PersonaUtils.estimateTokens(null));
    }

    @Test
    @DisplayName("estimateTokens → 纯中文")
    public void estimateTokensChinese() {
        int tokens = PersonaUtils.estimateTokens("你好世界");
        assertTrue(tokens > 0 && tokens <= 5);
    }

    @Test
    @DisplayName("estimateTokens → 纯英文")
    public void estimateTokensEnglish() {
        int tokens = PersonaUtils.estimateTokens("Hello World");
        assertTrue(tokens > 0 && tokens <= 6);
    }

    @Test
    @DisplayName("estimateTokens → 中英混合")
    public void estimateTokensMixed() {
        int tokens = PersonaUtils.estimateTokens("你好世界 Hello");
        assertTrue(tokens >= 3 && tokens <= 8);
    }

    // ========== estimateConversationTokens ==========

    @Test
    @DisplayName("estimateConversationTokens → 空消息返回 systemPromptTokens")
    public void estimateConversationTokensEmpty() {
        ArrayNode msgs = mapper.createArrayNode();
        int tokens = PersonaUtils.estimateConversationTokens(msgs, 2500);
        assertEquals(2500, tokens);
    }

    @Test
    @DisplayName("estimateConversationTokens → 有用户消息")
    public void estimateConversationTokensWithMsg() {
        ArrayNode msgs = mapper.createArrayNode();
        ObjectNode msg = msgs.addObject();
        msg.put("role", "user");
        msg.put("content", "你好，请帮我分析问题");
        int tokens = PersonaUtils.estimateConversationTokens(msgs, 2500);
        assertTrue(tokens > 2500);
    }

    // ========== shouldCompress ==========

    @Test
    @DisplayName("shouldCompress → 短消息不压缩(64K)")
    public void shouldCompressNo() {
        assertFalse(PersonaUtils.shouldCompress(2000, 64000, 0.70));
    }

    @Test
    @DisplayName("shouldCompress → 超过窗口 70% 压缩")
    public void shouldCompressYes() {
        assertTrue(PersonaUtils.shouldCompress(50000, 64000, 0.70));
    }

    @Test
    @DisplayName("shouldCompress → 等于是触发")
    public void shouldCompressAtBoundary() {
        assertTrue(PersonaUtils.shouldCompress(44800, 64000, 0.70));
    }

    // ========== compressMessages ==========

    @Test
    @DisplayName("compressMessages → 短消息不裁剪")
    public void compressMessagesShort() {
        ArrayNode msgs = createMessages(2);
        ArrayNode result = PersonaUtils.compressMessages(msgs, 3, null);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("compressMessages → 保留最后 N 轮 + 归档提示")
    public void compressMessagesLong() {
        ArrayNode msgs = createMessages(20); // 10 rounds
        ArrayNode result = PersonaUtils.compressMessages(msgs, 3, "【归档提示】对话已归档");
        // 保留 3 轮 × 2 + 1 条归档提示 = 7
        assertEquals(7, result.size());
        assertEquals("system", result.get(0).get("role").asText());
        assertEquals("【归档提示】对话已归档", result.get(0).get("content").asText());
    }

    @Test
    @DisplayName("compressMessages → archiveNote 为空不添加")
    public void compressMessagesNoArchiveNote() {
        ArrayNode msgs = createMessages(20);
        ArrayNode result = PersonaUtils.compressMessages(msgs, 3, "");
        assertEquals(6, result.size()); // 3 rounds × 2，无 archive note
    }

    // ========== helpers ==========

    private ArrayNode createMessages(int count) {
        ArrayNode msgs = mapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode msg = msgs.addObject();
            msg.put("role", i % 2 == 0 ? "user" : "assistant");
            msg.put("content", "消息 " + i);
        }
        return msgs;
    }
}
