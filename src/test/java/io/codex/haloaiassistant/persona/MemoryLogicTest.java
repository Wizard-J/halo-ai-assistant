package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

class MemoryLogicTest {
    final ObjectMapper json = new ObjectMapper();
    ArrayNode history(int count, int chars) {
        ArrayNode source = json.createArrayNode();
        for (int i = 0; i < count; i++) source.addObject().put("role", i % 2 == 0 ? "user" : "assistant")
                .put("content", "文".repeat(chars)).put("id", "m" + i).put("timestamp", "t" + i);
        return source;
    }
    @Test void bothWindowLimitsArchiveCompleteTurnsWithoutMutatingSource() {
        var source = history(44, 10); var original = source.deepCopy();
        var batch = MemoryLogic.plan("c", source, json.createObjectNode());
        assertEquals(4, batch.end()); assertEquals(original, source);
        var longMessages = history(10, 10000);
        assertEquals(6, ChatHistory.windowStart(longMessages));
        assertEquals(2, MemoryLogic.plan("c", longMessages, json.createObjectNode()).end());
        assertNull(MemoryLogic.plan("c", history(40, 10), json.createObjectNode()));
    }
    @Test void retriesAreIdempotentAndConcurrentProgressIsNotOverwritten() {
        var source = history(44, 10); var batch = MemoryLogic.plan("c", source, json.createObjectNode());
        var facts = json.createArrayNode(); facts.addObject().put("id", "fact1").put("text", "明确偏好");
        var state = MemoryLogic.merge(json.createObjectNode(), "c", batch, facts);
        assertEquals(state, MemoryLogic.merge(state, "c", batch, facts));
        assertNull(MemoryLogic.plan("c", source, state.path("cursors").path("c")));
        source.get(0).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)source.get(0)).put("content", "changed");
        assertThrows(IllegalStateException.class, () -> MemoryLogic.plan("c", source, state.path("cursors").path("c")));
    }
    @Test void evidenceMustComeFromUsersNotAssistantGuesses() throws Exception {
        var batch = MemoryLogic.plan("c", history(44, 10), json.createObjectNode());
        String valid = "{\"facts\":[{\"kind\":\"preference\",\"text\":\"偏好\",\"sourceIndex\":0,\"evidence\":\"文\"}]}";
        assertEquals(1, MemoryLogic.validate(valid, batch).size());
        assertThrows(IllegalArgumentException.class, () -> MemoryLogic.validate(valid.replace("Index\":0", "Index\":1"), batch));
        assertThrows(IllegalArgumentException.class, () -> MemoryLogic.validate(valid.replace("evidence\":\"文", "evidence\":\"捏造"), batch));
        assertThrows(IllegalArgumentException.class, () -> MemoryLogic.validate("{}", batch));
        assertEquals(0, MemoryLogic.validate("{\"facts\":[]}", batch).size());
    }
    @Test void legacyRepeatedMessagesDoNotReachModelTwice() {
        var source = history(44, 10);
        for (int i = 0; i < 44; i++) source.add(source.get(i).deepCopy());
        var first = MemoryLogic.plan("c", source, json.createObjectNode());
        var state = MemoryLogic.merge(json.createObjectNode(), "c", first, json.createArrayNode());
        assertEquals(44, first.messages().size());
        source.addObject().put("role", "user").put("content", "next");
        source.addObject().put("role", "assistant").put("content", "reply");
        var second = MemoryLogic.plan("c", source, state.path("cursors").path("c"));
        assertNotNull(second); assertTrue(second.messages().isEmpty());
    }
    @Test void capacityFailureLeavesOriginalLedgerAndMemoryUnchanged() {
        var batch = MemoryLogic.plan("c", history(44, 10), json.createObjectNode());
        var original = json.createObjectNode(); original.putArray("entries").addObject().put("id", "huge").put("text", "x".repeat(12000));
        var copy = original.deepCopy();
        assertThrows(IllegalStateException.class, () -> MemoryLogic.merge(original, "c", batch, json.createArrayNode()));
        assertEquals(copy, original); assertTrue(original.path("cursors").isMissingNode());
    }
    @Test void scopesAreIsolatedAndOversizedTurnsFailClosed() {
        assertNotEquals(PersonaMemoryService.name("user-a", "p"), PersonaMemoryService.name("user-b", "p"));
        assertNotEquals(PersonaMemoryService.name("user-a", "p"), PersonaMemoryService.name("user-a", "q"));
        assertThrows(IllegalStateException.class, () -> MemoryLogic.plan("c", history(44, 25000), json.createObjectNode()));
    }
    @Test void oldTodosAndBareConfirmationsDoNotBecomeDurableInstructions() {
        var entry = json.createObjectNode().put("kind", "decision").put("evidence", "确认");
        assertFalse(MemoryLogic.retainable(entry));
        entry.put("kind", "todo").put("evidence", "请持续跟进此事项").put("sourceTime", "2020-01-01T00:00:00Z");
        assertFalse(MemoryLogic.retainable(entry));
        entry.put("sourceTime", java.time.Instant.now().toString()); assertTrue(MemoryLogic.retainable(entry));
    }

}
