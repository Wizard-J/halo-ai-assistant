package io.codex.haloaiassistant.autoops;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceArticleReaderTest {
    private String body() {
        return "<p>" + "观点一：历史材料必须保留人物经历与论证上下文。".repeat(20) + "</p>"
                + "<p>" + "案例：一位作者在研究笔记中详细记录了争议的发生经过与不同解释。".repeat(20) + "</p>"
                + "<p>" + "观点二：从具体材料提炼结论时，需要区分作者陈述与编辑推断。".repeat(20) + "</p>";
    }

    @Test void selectsActualBodyAndPreservesQuotesButRemovesChrome() {
        var article = SourceArticleReader.extract("<nav>导航噪声</nav><main><article><div class='entry_content'>"
                + body() + "<blockquote><p>保留这一处原文论证和具体表达。</p></blockquote>"
                + "<aside>订阅噪声</aside><script>恶意脚本</script></div></article><footer>版权噪声</footer></main>", "https://example.com/a");
        assertTrue(article.text().contains("保留这一处原文"));
        assertTrue(article.text().contains("人物经历"));
        assertFalse(article.text().contains("噪声"));
        assertFalse(article.text().contains("恶意脚本"));
        assertFalse(article.truncated());
    }

    @Test void rejectsSummaryAndMissingSemanticArticleContainer() {
        assertThrows(IllegalStateException.class, () -> SourceArticleReader.extract("<article>短摘要</article>", "https://example.com"));
        assertThrows(IllegalStateException.class, () -> SourceArticleReader.extract("<body>" + body() + "</body>", "https://example.com"));
    }

    @Test void truncationIsExplicitAndNeverPresentedAsWholeText() {
        var article = SourceArticleReader.extract("<article>" + body().repeat(20) + "</article>", "https://example.com");
        assertTrue(article.truncated());
        assertEquals(SourceArticleReader.MAX_TEXT, article.text().length());
    }

    @Test void paywallIsNotAcceptedAsArticle() {
        assertThrows(IllegalStateException.class, () -> SourceArticleReader.extract(
                "<article>" + body() + "Subscribe to continue reading</article>", "https://example.com"));
    }

    @Test void privateUrlsAndNonHttpSchemesAreRejectedBeforeRequest() {
        var reader = new SourceArticleReader();
        for (String url : java.util.List.of("http://127.0.0.1/a", "http://10.1.2.3/a", "http://169.254.169.254/", "file:///etc/passwd", "http://[::1]/a"))
            assertThrows(IllegalArgumentException.class, () -> reader.read(url));
    }

    @Test void followsValidatedRedirectAndRejectsNonHtmlAndHttpErrors() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", exchange -> {
            byte[] bytes = ("<article>" + body() + "</article>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/article");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var reader = new SourceArticleReader(true);
            assertEquals(base + "/article", reader.read(base + "/redirect").url());
            assertThrows(IllegalStateException.class, () -> reader.read(base + "/json"));
            assertThrows(IllegalStateException.class, () -> reader.read(base + "/missing"));
        } finally { server.stop(0); }
    }
}
