package io.codex.haloaiassistant.autoops;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Comparator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/** Read public article pages only; never treat a feed summary or navigation as article text. */
@org.springframework.stereotype.Component
public class SourceArticleReader {
    static final int MAX_TEXT = 18000;
    private final boolean localFixtures;
    private final WebClient web = WebClient.builder()
            .clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .defaultHeader(HttpHeaders.USER_AGENT, "Halo-AI-Assistant/2 (article reader)").build();

    public SourceArticleReader() { this(false); }
    SourceArticleReader(boolean localFixtures) { this.localFixtures = localFixtures; }

    public SourceArticle read(String url) throws Exception {
        URI uri = URI.create(url);
        for (int redirect = 0; redirect <= 3; redirect++) {
            validate(uri);
            Page page = web.get().uri(uri).exchangeToMono(response -> {
                int status = response.statusCode().value();
                String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                String type = response.headers().asHttpHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                if (status >= 300 && status < 400) {
                    return response.releaseBody().thenReturn(new Page(status, location, type, ""));
                }
                if (status != 200 || type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                    return response.releaseBody().thenReturn(new Page(status, null, type, ""));
                }
                return response.bodyToMono(String.class).map(html -> new Page(status, null, type, html));
            }).timeout(Duration.ofSeconds(12)).block();
            if (page == null) throw new IllegalStateException("来源响应为空");
            if (page.status >= 300 && page.status < 400 && page.location != null) {
                uri = uri.resolve(page.location);
                continue;
            }
            if (page.html.isBlank()) throw new IllegalStateException("来源不是可读取的 HTML 正文，状态 " + page.status);
            return extract(page.html, uri.toString());
        }
        throw new IllegalStateException("来源重定向次数过多");
    }

    private void validate(URI uri) throws Exception {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("来源必须是公开 HTTP(S) 页面");
        }
        if (localFixtures) return; // Explicit package-private opt-in for local test servers only.
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
            throw new IllegalArgumentException("来源端口不允许");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                    || (bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127))
                throw new IllegalArgumentException("不能采集本机或私网来源");
        }
    }

    static SourceArticle extract(String html, String url) {
        var document = Jsoup.parse(html, url);
        document.select("script,style,noscript,nav,header,footer,aside,form,.comments,#comments,"
                + ".sharedaddy,.donation,.newsletter,[hidden],[aria-hidden=true]").remove();
        // Prefer article-body containers over a surrounding layout that also contains subscriptions.
        var bodies = document.select("[itemprop=articleBody],.entry-content,.entry_content,.post-content,.article-content,.post-body");
        if (bodies.isEmpty()) bodies = document.select("article,main,[role=main]");
        Element body = bodies.stream().max(Comparator.comparingInt(e -> e.select("p,blockquote,li").text().length()))
                .orElseThrow(() -> new IllegalStateException("没有找到独立文章正文"));
        String text = body.wholeText().replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n\n").trim();
        long paragraphs = body.select("p,blockquote,li").stream().filter(e -> e.text().length() >= 40).count();
        if (text.length() < 800 || paragraphs < 3 || body.select("a").text().length() > text.length() * 0.55)
            throw new IllegalStateException("正文材料不足或页面主要是导航，跳过候选");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("enable javascript and cookies") || lower.contains("verify you are human")
                || lower.contains("subscribe to continue reading") || lower.contains("sign in to continue reading"))
            throw new IllegalStateException("来源被验证页或付费墙阻挡");
        boolean truncated = text.length() > MAX_TEXT;
        return new SourceArticle(url, truncated ? text.substring(0, MAX_TEXT) : text, truncated);
    }

    public record SourceArticle(String url, String text, boolean truncated) { }
    private record Page(int status, String location, String type, String html) { }
}
