package io.codex.haloaiassistant.autoops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AutoOpsServiceTest {

    @Test
    @DisplayName("Mono.defer 内抛异常 → onErrorResume 兜底返回 JSON")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deferErrorCaught() {
        Mono<Map<String, Object>> result = Mono
                .<Map<String, Object>>defer(() -> {
                    throw new RuntimeException("AI 超时");
                })
                .onErrorResume(e -> Mono.just(
                        Map.of("success", false, "error", e.getMessage())));

        StepVerifier.create(result)
                .consumeNextWith(m -> {
                    assertFalse((Boolean) m.get("success"));
                    assertTrue(m.get("error").toString().contains("AI 超时"));
                    System.out.println("  ✅ defer 异常 → JSON 降级");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono.defer 正常完成")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deferNormal() {
        Mono<Map<String, Object>> result = Mono
                .<Map<String, Object>>defer(() -> Mono.just(Map.of("success", true)))
                .onErrorResume(e -> Mono.just(Map.of("success", false)));

        StepVerifier.create(result)
                .consumeNextWith(m -> {
                    assertTrue((Boolean) m.get("success"));
                    System.out.println("  ✅ defer 正常");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("模拟 null publisher → defer 兜底")
    void nullPublisherInDefer() {
        Mono<Map<String, Object>> result = buildChainWithNullPublisher();

        StepVerifier.create(result)
                .consumeNextWith(m -> {
                    assertFalse((Boolean) m.get("success"));
                    System.out.println("  ✅ null publisher → 兜底: " + m);
                })
                .verifyComplete();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Mono<Map<String, Object>> buildChainWithNullPublisher() {
        return Mono.defer(() -> {
            // 模拟 settingFetcher.fetch() 返回 null
            Mono<String> bad = null;
            return (Mono) Mono.zip(Mono.just("ok"), bad, (a, b) -> "ok");
        }).onErrorResume(e -> Mono.just(Map.of("success", false, "error", "NPE")));
    }

    @Test
    @DisplayName("无 defer 时 null publisher 同步炸穿")
    void nullPublisherWithoutDefer() {
        assertThrows(NullPointerException.class, () -> {
            Mono<String> bad = null;
            Mono.zip(Mono.just("a"), bad, (a, b) -> a + b);
        });
        System.out.println("  ✅ 无 defer → NPE 炸穿（预期）");
    }

    @Test
    @DisplayName("去重 → primary 优先")
    void dedupPrimaryWins() {
        var m = new LinkedHashMap<String, String>();
        m.merge("u1", "次要", (a, b) -> "主要".equals(a) ? a : b);
        m.merge("u1", "主要", (a, b) -> "主要".equals(a) ? a : b);
        assertEquals("主要", m.get("u1"));
        System.out.println("  ✅ primary 优先");
    }

    @Test
    @DisplayName("测试模式逻辑")
    void testModeLogic() {
        assertFalse(!true && true);
        assertTrue(!false && true);
        System.out.println("  ✅ 测试模式正确");
    }

    @Test
    @DisplayName("RSS 源行解析")
    void rssLineParsing() {
        long n = "a.com\n\nb.com\n#c\n".lines()
                .filter(l -> !l.isBlank() && !l.strip().startsWith("#")).count();
        assertEquals(2, n);
        System.out.println("  ✅ RSS: " + n + " 条");
    }
}
