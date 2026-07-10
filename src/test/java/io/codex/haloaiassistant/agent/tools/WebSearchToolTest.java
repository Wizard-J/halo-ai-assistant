package io.codex.haloaiassistant.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {

    @Test
    void parseResultsExtractsCompressedDuckDuckGoResults() {
        String html = """
                <div class="result">
                  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fnews&amp;rut=abc">
                    Example &amp; News
                  </a>
                  <a class="result__snippet">A short <b>summary</b> about the current topic.</a>
                </div>
                <div class="result">
                  <a class="result__a" href="https://example.org/plain">Plain Result</a>
                  <div class="result__snippet">Another summary.</div>
                </div>
                """;

        List<WebSearchTool.SearchResult> results = WebSearchTool.parseResults(html, 5);

        assertEquals(2, results.size());
        assertEquals("Example & News", results.get(0).title());
        assertEquals("https://example.com/news", results.get(0).url());
        assertEquals("A short summary about the current topic.", results.get(0).snippet());
        assertEquals("https://example.org/plain", results.get(1).url());
    }

    @Test
    void parseResultsRespectsLimit() {
        String html = """
                <a class="result__a" href="https://one.example">One</a><div class="result__snippet">First</div>
                <a class="result__a" href="https://two.example">Two</a><div class="result__snippet">Second</div>
                """;

        List<WebSearchTool.SearchResult> results = WebSearchTool.parseResults(html, 1);

        assertEquals(1, results.size());
        assertTrue(results.get(0).title().contains("One"));
    }
}
