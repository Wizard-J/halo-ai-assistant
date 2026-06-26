package io.codex.haloaiassistant.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Focused unit tests for AgentService SSE parser internals.
 *
 * Tests call private methods via reflection to verify:
 * - parseSseChunk handles split events, merged events, content output
 * - handleStreamData processes delta correctly
 * - reasoning_content is not emitted as visible content
 * - tool_calls are accumulated silently (not emitted)
 * - data: [DONE] produces no output
 */
class AgentServiceStreamingTest {

    private AgentService agentService;
    private Class<?> stateClass;
    private Constructor<?> stateCtor;
    private Method parseSseChunkMethod;
    private Method handleStreamDataMethod;

    @BeforeEach
    void setUp() throws Exception {
        agentService = new AgentService(null);
        stateClass = Class.forName("io.codex.haloaiassistant.agent.AgentService$StreamState");
        stateCtor = stateClass.getDeclaredConstructor();
        stateCtor.setAccessible(true);

        parseSseChunkMethod = AgentService.class.getDeclaredMethod(
                "parseSseChunk", StringBuilder.class, String.class, stateClass);
        parseSseChunkMethod.setAccessible(true);

        handleStreamDataMethod = AgentService.class.getDeclaredMethod(
                "handleStreamData", String.class, stateClass);
        handleStreamDataMethod.setAccessible(true);
    }

    private Object newState() throws Exception {
        return stateCtor.newInstance();
    }

    private Flux<String> callParseSseChunk(StringBuilder buffer, String chunk, Object state) throws Exception {
        return (Flux<String>) parseSseChunkMethod.invoke(agentService, buffer, chunk, state);
    }

    private String callHandleStreamData(String data, Object state) throws Exception {
        return (String) handleStreamDataMethod.invoke(agentService, data, state);
    }

    // ========== findSseEventEnd ==========

    @Test @DisplayName("findSseEventEnd 找到 \\n\\n 边界")
    void testFindSseEventEnd_DoubleNewline() throws Exception {
        Method m = AgentService.class.getDeclaredMethod("findSseEventEnd", StringBuilder.class);
        m.setAccessible(true);
        int pos = (int) m.invoke(agentService, new StringBuilder("data: hello\n\n"));
        assertEquals(13, pos);
    }

    @Test @DisplayName("findSseEventEnd 找到 \\r\\n\\r\\n 边界")
    void testFindSseEventEnd_CrLf() throws Exception {
        Method m = AgentService.class.getDeclaredMethod("findSseEventEnd", StringBuilder.class);
        m.setAccessible(true);
        int pos = (int) m.invoke(agentService, new StringBuilder("data: hello\r\n\r\n"));
        assertEquals(15, pos);
    }

    @Test @DisplayName("findSseEventEnd 不完整事件返回 -1")
    void testFindSseEventEnd_Incomplete() throws Exception {
        Method m = AgentService.class.getDeclaredMethod("findSseEventEnd", StringBuilder.class);
        m.setAccessible(true);
        int pos = (int) m.invoke(agentService, new StringBuilder("data: hello"));
        assertEquals(-1, pos);
    }

    // ========== handleStreamData ==========

    @Test @DisplayName("handleStreamData 提取 content")
    void testHandleStreamData_Content() throws Exception {
        Object state = newState();
        String json = "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}";
        String result = callHandleStreamData(json, state);
        assertEquals("你好", result);
    }

    @Test @DisplayName("handleStreamData 空 content 返回空串")
    void testHandleStreamData_EmptyContent() throws Exception {
        Object state = newState();
        String json = "{\"choices\":[{\"delta\":{\"content\":\"\"}}]}";
        String result = callHandleStreamData(json, state);
        assertEquals("", result);
    }

    @Test @DisplayName("handleStreamData 提取 finish_reason")
    void testHandleStreamData_FinishReason() throws Exception {
        java.lang.reflect.Field getField = stateClass.getDeclaredField("finishReason");
        getField.setAccessible(true);

        Object state = newState();
        String json = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
        callHandleStreamData(json, state);

        assertEquals("stop", getField.get(state));
    }

    @Test @DisplayName("handleStreamData 跳过 reasoning_content 不输出")
    void testHandleStreamData_ReasoningContentNotEmitted() throws Exception {
        Object state = newState();
        // reasoning_content 和 content 同时出现时只返回 content
        String json = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\",\"content\":\"hello\"}}]}";
        String result = callHandleStreamData(json, state);
        assertEquals("hello", result, "只应返回 content，不应返回 reasoning");

        // 只有 reasoning_content 时不应输出
        Object state2 = newState();
        String json2 = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"inner thoughts\"}}]}";
        String result2 = callHandleStreamData(json2, state2);
        assertEquals("", result2, "只有 reasoning 不应输出到用户");
    }

    @Test @DisplayName("handleStreamData tool_calls 不输出，但 state 能累计")
    void testHandleStreamData_ToolCallsSilent() throws Exception {
        Method getToolCalls = stateClass.getDeclaredMethod("hasToolCalls");
        getToolCalls.setAccessible(true);
        Method getContent = stateClass.getDeclaredField("content").getType()
                .getMethod("toString");
        // We'll use a simpler approach: verify tool_calls line doesn't produce content
        Object state = newState();
        String json = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"test\",\"arguments\":\"{}\"}}]}}]}";
        String result = callHandleStreamData(json, state);
        assertEquals("", result, "tool_calls delta 不应输出 content");
        assertTrue((boolean) getToolCalls.invoke(state), "state 应标记 hasToolCalls");
    }

    @Test @DisplayName("handleStreamData tool_calls 分段 arguments 能累计")
    void testHandleStreamData_ToolCallsAccumulate() throws Exception {
        Object state = newState();
        // 第一段 tool_call delta
        String chunk1 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"createArticle\",\"arguments\":\"{\\\"title\\\":\\\"He\"}}]}}]}";
        callHandleStreamData(chunk1, state);
        // 第二段 tool_call delta（继续 arguments）
        String chunk2 = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"llo\\\"}\"}}]}}]}";
        callHandleStreamData(chunk2, state);

        // 验证 state 中 toolCalls map 有正确的 name 和 arguments
        var toolCallsField = stateClass.getDeclaredField("toolCalls");
        toolCallsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, Object> tcMap = (java.util.Map<Integer, Object>) toolCallsField.get(state);
        assertEquals(1, tcMap.size(), "应有一个 tool call（index=0）");

        // 读取 ToolCallAccumulator 的字段
        Object acc = tcMap.get(0);
        assertNotNull(acc);
        var accClass = acc.getClass();
        var accIdField = accClass.getDeclaredField("id");
        accIdField.setAccessible(true);
        var accNameField = accClass.getDeclaredField("name");
        accNameField.setAccessible(true);
        var accArgsField = accClass.getDeclaredField("arguments");
        accArgsField.setAccessible(true);
        assertEquals("call_x", accIdField.get(acc));
        assertEquals("createArticle", accNameField.get(acc));
        String args = accArgsField.get(acc).toString();
        assertTrue(args.contains("title"), "arguments 应包含 title");
        assertTrue(args.contains("Hello"), "arguments 应包含合并后的 Hello");
    }

    // ========== parseSseChunk 组合测试 ==========

    @Test @DisplayName("parseSseChunk 单个完整 event 输出 content")
    void testParseSseChunk_SingleEvent() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n";

        Flux<String> flux = callParseSseChunk(buffer, chunk, state);
        StepVerifier.create(flux)
                .expectNext("你好")
                .verifyComplete();
    }

    @Test @DisplayName("parseSseChunk 两个 event 在一个 chunk 中")
    void testParseSseChunk_MergedEvents() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                     + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n";

        Flux<String> flux = callParseSseChunk(buffer, chunk, state);
        StepVerifier.create(flux)
                .expectNext("你")
                .expectNext("好")
                .verifyComplete();
    }

    @Test @DisplayName("parseSseChunk JSON 被拆成两段 chunk 能缓冲拼接")
    void testParseSseChunk_SplitChunk() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();

        // 第一次：不完整
        String incomplete = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}";
        Flux<String> flux1 = callParseSseChunk(buffer, incomplete, state);
        StepVerifier.create(flux1).verifyComplete(); // 没有输出

        // 第二次：补齐
        String rest = "]}\n\n";
        Flux<String> flux2 = callParseSseChunk(buffer, rest, state);
        StepVerifier.create(flux2)
                .expectNext("你")
                .verifyComplete();
    }

    @Test @DisplayName("parseSseChunk data: [DONE] 不输出")
    void testParseSseChunk_DoneMarker() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                     + "data: [DONE]\n\n";

        Flux<String> flux = callParseSseChunk(buffer, chunk, state);
        StepVerifier.create(flux)
                .expectNext("OK")
                .verifyComplete(); // [DONE] 不应产生额外输出
    }

    @Test @DisplayName("parseSseChunk reasoning_content 不输出到 Flux")
    void testParseSseChunk_ReasoningNotEmitted() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();
        // 只有 reasoning 的 event
        String chunk = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n\n"
                     // 有 content 的 event
                     + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n";

        Flux<String> flux = callParseSseChunk(buffer, chunk, state);
        StepVerifier.create(flux)
                .expectNext("answer") // 只有 content 应输出
                .verifyComplete();
    }

    @Test @DisplayName("parseSseChunk tool_calls event 不输出 content 到 Flux")
    void testParseSseChunk_ToolCallsSilentInFlux() throws Exception {
        Object state = newState();
        StringBuilder buffer = new StringBuilder();
        String chunk = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}\n\n";

        Flux<String> flux = callParseSseChunk(buffer, chunk, state);
        StepVerifier.create(flux).verifyComplete(); // 不应有任何 content 输出

        // 但 state 应有 tool_calls
        Method hasTc = stateClass.getDeclaredMethod("hasToolCalls");
        hasTc.setAccessible(true);
        assertTrue((boolean) hasTc.invoke(state));
    }
}
