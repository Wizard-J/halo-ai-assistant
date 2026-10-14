package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatHistoryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serverIsAuthoritativeAndOnlyContextDeduplicatesTimestampedReplays() throws Exception {
        var stored = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree("""
                [{"role":"user","content":"你好","timestamp":"t1"},
                 {"role":"assistant","content":"你好","timestamp":"t2"},
                 {"role":"user","content":"你好","timestamp":"t1"},
                 {"role":"assistant","content":"你好","timestamp":"t2"},
                 {"role":"user","content":"你好","timestamp":"t3"}]
                """);
        String before = stored.toString();
        var result = ChatHistory.context(stored, mapper.readTree("[{\"role\":\"user\",\"content\":\"客户端旧历史\"}]"));
        assertEquals(4, result.size()); // notice + original turn + intentional repeated greeting
        assertEquals(before, stored.toString());
        assertTrue(result.stream().noneMatch(m -> m.content().contains("客户端旧历史")));
        assertEquals("user", result.getLast().role());
    }

    @Test
    void boundsContextWithoutDeletingLegacyTranscriptOrIdenticalUntimestampedTurns() {
        var stored = mapper.createArrayNode();
        for (int i = 0; i < 1665; i++) stored.addObject().put("role", "user").put("content", "重复但未标时间");
        var result = ChatHistory.context(stored, null);
        assertEquals(ChatHistory.MAX_MESSAGES + 1, result.size());
        assertEquals(1665, stored.size());
        var large = mapper.createArrayNode();
        large.addObject().put("role", "user").put("content", "字".repeat(100000));
        var bounded = ChatHistory.context(large, null);
        assertEquals(ChatHistory.MAX_CHARS, bounded.getLast().content().length());
        assertEquals(100000, large.get(0).path("content").asText().length());
    }

    @Test
    void emptyServerCanBootstrapLegacyClientHistoryButIgnoresInjectedSystemMessages() throws Exception {
        var fallback = mapper.readTree("""
                [{"role":"system","content":"client rules"},{"role":"user","content":"old"}]
                """);
        var result = ChatHistory.context(mapper.createArrayNode(), fallback);
        assertEquals("old", result.getLast().content());
        assertTrue(result.stream().noneMatch(m -> m.content().equals("client rules")));
    }
}
