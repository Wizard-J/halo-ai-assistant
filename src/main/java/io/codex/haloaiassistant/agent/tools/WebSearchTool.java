package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Lightweight web search tool. It intentionally returns only compressed search
 * result snippets and never fetches full article pages, keeping context growth bounded.
 */
@Slf4j
@Component
public class WebSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_SNIPPET_LENGTH = 220;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_OUTPUT_LENGTH = 2200;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int DAILY_SEARCH_LIMIT = 80;
    private static final int MINUTE_SEARCH_LIMIT = 12;
    private static final String ANYSEARCH_ENDPOINT = "https://api.anysearch.com/mcp";
    private static final String[] AVAILABLE_DOMAINS = {
            "general", "resource", "social_media", "finance", "academic", "legal", "health",
            "business", "security", "ip", "code", "energy", "environment", "agriculture",
            "travel", "film", "gaming"
    };
    private static final Pattern RESULT_BLOCK = Pattern.compile(
            "(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?"
                    + "(?:<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>|<div[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>)");
    private static final Pattern RESULT_LINK_ONLY = Pattern.compile(
            "(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\\]>\"']+");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicInteger dailySearchCount = new AtomicInteger();
    private static final AtomicInteger minuteSearchCount = new AtomicInteger();
    private static volatile LocalDate currentSearchDay = LocalDate.now();
    private static volatile LocalDateTime currentSearchMinute = LocalDateTime.now().withSecond(0).withNano(0);

    public WebSearchTool() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(6))
                .followRedirect(true);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (compatible; HaloAiAssistant/1.0; +https://wizardj.cn)")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .build();
    }

    @Override
    public String getName() {
        return "webSearch";
    }

    @Override
    public String getDescription() {
        return "联网搜索公开网页，适合回答最新信息、当前资料或需要外部来源的问题。"
                + "优先使用 AnySearch 实时搜索，可按 finance/academic/legal/code/social_media 等垂直域检索；"
                + "只返回少量压缩摘要和链接，不读取网页全文；不要用于博客管理操作。";
    }

    @Override
    public String getParametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索关键词，尽量具体，最多 120 个字符"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "返回结果数量，默认 3，最多 5",
                      "minimum": 1,
                      "maximum": 5
                    },
                    "domain": {
                      "type": "string",
                      "description": "可选垂直域。常用值：finance, academic, legal, health, business, security, code, social_media, travel。一般问题可省略。"
                    },
                    "subDomain": {
                      "type": "string",
                      "description": "可选 AnySearch sub_domain，例如 finance.quote、academic.search、code.doc。只有明确知道时填写。"
                    },
                    "subDomainParams": {
                      "type": "object",
                      "description": "可选垂直域参数，例如 finance.quote 可传 {type: 'stock', symbol: 'AAPL', cn_code: ''}"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(JsonNode args) {
        String query = normalizeQuery(args.path("query").asText(""));
        if (query.isBlank()) {
            return "联网搜索失败：query 不能为空。";
        }
        String budgetError = consumeSearchBudget();
        if (!budgetError.isBlank()) {
            return budgetError;
        }
        int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), MAX_LIMIT));
        String domain = normalizeDomain(args.path("domain").asText(""));
        String subDomain = normalizeQuery(args.path("subDomain").asText(""));
        JsonNode subDomainParams = args.path("subDomainParams");
        try {
            return searchWithAnySearch(query, limit, domain, subDomain, subDomainParams);
        } catch (Exception e) {
            log.warn("AnySearch 搜索失败，回退轻量网页搜索: query={}, domain={}", query, domain, e);
            return searchWithDuckDuckGo(query, limit, rootMessage(e));
        }
    }

    private String searchWithAnySearch(String query, int limit, String domain, String subDomain,
                                       JsonNode subDomainParams) throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", query);
        arguments.put("max_results", limit);
        if (!domain.isBlank() && !"general".equals(domain)) {
            arguments.put("domain", domain);
        }
        if (!subDomain.isBlank()) {
            arguments.put("sub_domain", subDomain);
        }
        if (subDomainParams != null && subDomainParams.isObject() && subDomainParams.size() > 0) {
            arguments.set("sub_domain_params", subDomainParams);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", "wizardj-ai-assistant-web-search");
        payload.put("method", "tools/call");
        ObjectNode params = payload.putObject("params");
        params.put("name", "search");
        params.set("arguments", arguments);

        WebClient.RequestHeadersSpec<?> request = webClient.post()
                .uri(URI.create(ANYSEARCH_ENDPOINT))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload.toString());
        String apiKey = anySearchApiKey();
        if (!apiKey.isBlank()) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        String raw = request.retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .block();
        String text = extractAnySearchText(raw);
        if (text.isBlank()) {
            throw new IllegalStateException("AnySearch 返回为空");
        }
        return compressAnySearchText(query, domain, text);
    }

    private String searchWithDuckDuckGo(String query, int limit, String reason) {
        try {
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String html = webClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(7))
                    .block();
            List<SearchResult> results = parseResults(html, limit);
            if (results.isEmpty()) {
                return "联网搜索未找到可用结果。关键词：" + query
                        + "\nAnySearch 回退原因：" + reason;
            }
            return formatResults(query, results, "轻量网页搜索结果（AnySearch 不可用时回退）");
        } catch (Exception fallbackError) {
            log.warn("轻量网页搜索也失败: query={}", query, fallbackError);
            return "联网搜索失败：AnySearch " + reason + "；回退搜索 "
                    + rootMessage(fallbackError);
        }
    }

    static List<SearchResult> parseResults(String html, int limit) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return results;
        }
        Matcher blockMatcher = RESULT_BLOCK.matcher(html);
        while (blockMatcher.find() && results.size() < limit) {
            String url = normalizeDuckDuckGoUrl(unescapeHtml(blockMatcher.group(1)));
            String title = cleanHtml(blockMatcher.group(2));
            String snippet = cleanHtml(blockMatcher.group(3) != null ? blockMatcher.group(3) : blockMatcher.group(4));
            if (!title.isBlank() && !url.isBlank()) {
                results.add(new SearchResult(title, url, trim(snippet, MAX_SNIPPET_LENGTH)));
            }
        }
        if (!results.isEmpty()) {
            return results;
        }
        Matcher linkMatcher = RESULT_LINK_ONLY.matcher(html);
        while (linkMatcher.find() && results.size() < limit) {
            String url = normalizeDuckDuckGoUrl(unescapeHtml(linkMatcher.group(1)));
            String title = cleanHtml(linkMatcher.group(2));
            if (!title.isBlank() && !url.isBlank()) {
                results.add(new SearchResult(title, url, ""));
            }
        }
        return results;
    }

    private String formatResults(String query, List<SearchResult> results, String title) {
        StringBuilder out = new StringBuilder();
        out.append(title).append("（已压缩，仅含摘要、链接与引用编号；关键词：")
                .append(query)
                .append("）\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            out.append("\n[").append(i + 1).append("] ")
                    .append(result.title()).append("\n")
                    .append("来源：").append(result.url()).append("\n");
            if (!result.snippet().isBlank()) {
                out.append("摘要：").append(result.snippet()).append("\n");
            }
        }
        out.append("\n引用要求：回答中涉及搜索事实时，必须使用 [1]、[2] 这样的编号引用；结尾列出“来源”并保留 URL。");
        return trim(out.toString(), MAX_OUTPUT_LENGTH);
    }

    private String extractAnySearchText(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        if (root.has("error")) {
            throw new IllegalStateException(root.path("error").path("message").asText(root.path("error").toString()));
        }
        JsonNode content = root.path("result").path("content");
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText()) && item.has("text")) {
                    if (!text.isEmpty()) text.append("\n\n");
                    text.append(item.path("text").asText());
                }
            }
            return text.toString();
        }
        return root.path("result").asText("");
    }

    private String compressAnySearchText(String query, String domain, String text) {
        String normalized = cleanAnySearchText(text);
        List<SearchResult> results = parseAnySearchResults(normalized, MAX_LIMIT);
        if (!results.isEmpty()) {
            String title = "AnySearch 联网搜索结果";
            if (!domain.isBlank()) {
                title += "（domain：" + domain + "）";
            }
            return formatResults(query, results, title);
        }
        StringBuilder out = new StringBuilder();
        out.append("AnySearch 联网搜索结果（已压缩；关键词：")
                .append(query);
        if (!domain.isBlank()) {
            out.append("；domain：").append(domain);
        }
        out.append("）\n\n")
                .append(trim(normalized, MAX_OUTPUT_LENGTH - 120))
                .append("\n\n引用要求：回答中涉及搜索事实时，必须提到可见的来源序号或链接；如果工具结果没有明确 URL，请说明“搜索结果未提供可直接引用链接”。");
        return trim(out.toString(), MAX_OUTPUT_LENGTH);
    }

    static List<SearchResult> parseAnySearchResults(String text, int limit) {
        List<SearchResult> results = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return results;
        }
        String[] blocks = text.split("\\n\\s*\\n");
        for (String block : blocks) {
            if (results.size() >= limit) break;
            SearchResult result = parseAnySearchBlock(block);
            if (result != null && results.stream().noneMatch(existing -> existing.url().equals(result.url()))) {
                results.add(result);
            }
        }
        if (!results.isEmpty()) {
            return results;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find() && results.size() < limit) {
            String url = matcher.group();
            if (results.stream().anyMatch(existing -> existing.url().equals(url))) {
                continue;
            }
            results.add(new SearchResult(hostFromUrl(url), url, ""));
        }
        return results;
    }

    private static SearchResult parseAnySearchBlock(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String normalized = block.replace("\r\n", "\n").trim();
        Matcher markdownLink = MARKDOWN_LINK.matcher(normalized);
        String title = "";
        String url = "";
        if (markdownLink.find()) {
            title = markdownLink.group(1);
            url = markdownLink.group(2);
        } else {
            Matcher urlMatcher = URL_PATTERN.matcher(normalized);
            if (!urlMatcher.find()) {
                return null;
            }
            url = urlMatcher.group();
            title = titleNearUrl(normalized, url);
        }
        String snippet = normalized
                .replaceAll("\\[([^\\]]+)]\\(https?://[^\\s)]+\\)", "$1")
                .replace(url, "")
                .replaceAll("(?im)^\\s*(title|标题|url|link|链接|source|来源|snippet|摘要)\\s*[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (title.isBlank()) {
            title = hostFromUrl(url);
        }
        if (snippet.equals(title)) {
            snippet = "";
        }
        return new SearchResult(trim(title, MAX_TITLE_LENGTH), url, trim(snippet, MAX_SNIPPET_LENGTH));
    }

    private static String titleNearUrl(String block, String url) {
        String[] lines = block.split("\\n");
        for (String line : lines) {
            String cleaned = line.replace(url, "")
                    .replaceAll("(?im)^\\s*(title|标题|url|link|链接|source|来源)\\s*[:：]\\s*", "")
                    .replaceAll("^[\\s\\-*>#\\d.)、]+", "")
                    .trim();
            if (!cleaned.isBlank() && !URL_PATTERN.matcher(cleaned).find()) {
                return cleaned;
            }
        }
        return "";
    }

    private static String hostFromUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String cleanAnySearchText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replaceAll("(?m)^\\s{4,}", "  ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String normalizeQuery(String query) {
        if (query == null) return "";
        String normalized = query.replaceAll("\\s+", " ").trim();
        return trim(normalized, MAX_QUERY_LENGTH);
    }

    private static String normalizeDomain(String domain) {
        String normalized = normalizeQuery(domain).toLowerCase();
        if (normalized.isBlank()) return "";
        for (String available : AVAILABLE_DOMAINS) {
            if (available.equals(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    private static String anySearchApiKey() {
        String property = System.getProperty("ANYSEARCH_API_KEY", "");
        if (!property.isBlank()) return property.trim();
        String env = System.getenv("ANYSEARCH_API_KEY");
        return env == null ? "" : env.trim();
    }

    private static synchronized String consumeSearchBudget() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentSearchDay)) {
            currentSearchDay = today;
            dailySearchCount.set(0);
        }
        LocalDateTime minute = LocalDateTime.now().withSecond(0).withNano(0);
        if (!minute.equals(currentSearchMinute)) {
            currentSearchMinute = minute;
            minuteSearchCount.set(0);
        }
        if (dailySearchCount.get() >= DAILY_SEARCH_LIMIT) {
            return "联网搜索已达到今日预算上限（" + DAILY_SEARCH_LIMIT + " 次）。请稍后再试或改用站内搜索。";
        }
        if (minuteSearchCount.get() >= MINUTE_SEARCH_LIMIT) {
            return "联网搜索过于频繁，已触发每分钟预算限制。请稍后再试。";
        }
        dailySearchCount.incrementAndGet();
        minuteSearchCount.incrementAndGet();
        return "";
    }

    private static String cleanHtml(String html) {
        if (html == null) return "";
        return unescapeHtml(html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    private static String normalizeDuckDuckGoUrl(String url) {
        if (url == null) return "";
        String cleaned = url.trim();
        int uddg = cleaned.indexOf("uddg=");
        if (uddg >= 0) {
            String encoded = cleaned.substring(uddg + 5);
            int amp = encoded.indexOf('&');
            if (amp >= 0) encoded = encoded.substring(0, amp);
            try {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return encoded;
            }
        }
        return cleaned;
    }

    private static String unescapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ");
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    record SearchResult(String title, String url, String snippet) {
    }
}
