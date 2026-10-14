package io.codex.haloaiassistant.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Blocking credential operations: callers must use boundedElastic. Never expose tokens to UI. */
@Service
public class ChatGptCredentials implements org.springframework.beans.factory.DisposableBean {
    static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;
    private final HttpClient http;
    private HttpClient sharedHttp;
    private String sharedProxyConfig;
    private final java.util.List<HttpClient> managedHttp = new java.util.ArrayList<>();

    public ChatGptCredentials() {
        this(Path.of(System.getenv().getOrDefault("HALO_AI_CHATGPT_DIR",
                defaultDirectory())));
    }

    ChatGptCredentials(Path directory) {
        this(directory, null);
    }

    ChatGptCredentials(Path directory, HttpClient http) {
        this.directory = directory;
        this.http = http;
    }

    static String defaultDirectory() {
        String workDir = System.getenv("HALO_WORK_DIR");
        return (workDir == null || workDir.isBlank() ? System.getProperty("user.home") : workDir)
                + "/.config/halo-ai-assistant/chatgpt";
    }

    /** Only subscription traffic uses this optional private HTTP CONNECT proxy. */
    synchronized HttpClient httpClient() {
        if (http != null) return http;
        Path path = directory.resolve("proxy.json");
        String proxyConfig = "";
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                requireProtectedFile(path);
                proxyConfig = Files.readString(path);
                if (proxyConfig.isBlank()) throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new IllegalStateException("ChatGPT 专用代理配置无效或权限过宽，请检查 proxy.json");
            }
        }
        if (sharedHttp != null && proxyConfig.equals(sharedProxyConfig)) return sharedHttp;
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (!proxyConfig.isBlank()) {
            try {
                JsonNode config = mapper.readTree(proxyConfig);
                String host = config.path("host").asText();
                int port = config.path("port").asInt();
                if (host.isBlank() || host.contains("/") || host.contains("@") || port < 1 || port > 65535)
                    throw new IllegalArgumentException();
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            } catch (Exception e) {
                throw new IllegalStateException("ChatGPT 专用代理配置无效或权限过宽，请检查 proxy.json");
            }
        }
        sharedHttp = builder.build();
        managedHttp.add(sharedHttp);
        sharedProxyConfig = proxyConfig;
        return sharedHttp;
    }

    @Override
    public synchronized void destroy() {
        // Non-blocking graceful shutdown; injected test/externally owned clients are excluded.
        managedHttp.forEach(HttpClient::shutdown);
        managedHttp.clear();
    }

    public synchronized Map<String, Object> status() {
        try {
            ObjectNode profile = readProfile();
            return Map.of("connected", true, "account", profile.path("email").asText("ChatGPT"),
                    "expiresAt", expiresAt(profile).toString(), "message", "已导入订阅授权；请检查连接验证可用性");
        } catch (Exception e) {
            return Map.of("connected", false, "message", safeError(e));
        }
    }

    public synchronized String accessToken() {
        hostId();
        ObjectNode profile = readProfile();
        if (expiresAt(profile).isAfter(Instant.now().plusSeconds(60))) {
            return profile.path("access_token").asText();
        }
        // Synchronized refresh + atomic replacement prevent refresh-token rotation races in this runtime.
        JsonNode replacement = tokenRequest("https://auth.openai.com/api/accounts/oauth/token",
                "grant_type=refresh_token&client_id=" + encode(profile.path("client_id").asText())
                        + "&refresh_token=" + encode(profile.path("refresh_token").asText())
                        + "&resource=" + encode("https://api.openai.com/v1"));
        if (replacement.path("access_token").asText().isBlank()
                || replacement.path("refresh_token").asText().isBlank()
                || replacement.path("expires_in").asLong() <= 0) {
            throw new IllegalStateException("ChatGPT 续期响应不完整，请重新授权");
        }
        profile.put("access_token", replacement.path("access_token").asText());
        profile.put("refresh_token", replacement.path("refresh_token").asText());
        profile.put("expires_in", replacement.path("expires_in").asLong());
        profile.put("saved_at", Instant.now().toString());
        if (replacement.hasNonNull("scope")) {
            var scopes = profile.putArray("scopes");
            for (String scope : replacement.path("scope").asText().split(" +")) scopes.add(scope);
        }
        if (replacement.hasNonNull("id_token")) profile.put("id_token", replacement.path("id_token").asText());
        requirePlanScope(profile);
        writeProtected(directory.resolve("credentials.json"), profile);
        return profile.path("access_token").asText();
    }

    /** The runtime owns its host ID; an imported laptop profile must never replace it. */
    public synchronized String hostId() {
        try {
            prepareDirectory();
            Path path = directory.resolve("host.json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                var host = mapper.createObjectNode().put("ext_agent_host_id", "urn:uuid:" + UUID.randomUUID());
                writeProtected(path, host);
            }
            requireProtectedFile(path);
            String id = mapper.readTree(Files.readString(path)).path("ext_agent_host_id").asText();
            if (!id.startsWith("urn:uuid:") && !id.startsWith("urn:ietf:params:oauth:jwk-thumbprint:")
                    && !id.startsWith("did:key:")) throw new IllegalStateException("ChatGPT 主机标识无效");
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 ChatGPT 运行目录，请检查目录权限");
        }
    }

    public synchronized Map<String, Object> disconnect() {
        boolean revoked = false;
        try {
            ObjectNode profile = readProfile();
            HttpRequest discovery = HttpRequest.newBuilder(URI.create(
                    "https://auth.openai.com/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            var response = httpClient().send(discovery, HttpResponse.BodyHandlers.ofString());
            URI endpoint = URI.create(mapper.readTree(response.body()).path("revocation_endpoint").asText());
            if (!"https".equals(endpoint.getScheme()) || !"auth.openai.com".equals(endpoint.getHost())) {
                throw new IllegalStateException("无效的撤销端点");
            }
            tokenRequest(endpoint.toString(), "token=" + encode(profile.path("refresh_token").asText())
                    + "&token_type_hint=refresh_token&client_id=" + encode(profile.path("client_id").asText()));
            revoked = true;
        } catch (Exception ignored) {
            // Still stop local usage. Never include HTTP response bodies or credentials in logs/UI.
        }
        try {
            Files.deleteIfExists(directory.resolve("credentials.json"));
        } catch (Exception e) {
            throw new IllegalStateException("无法移除本机授权文件，请检查权限");
        }
        return Map.of("connected", false, "revoked", revoked, "message", revoked
                ? "已断开并撤销授权" : "本机授权已移除；远程撤销未确认，请在 ChatGPT 设置中断开此应用");
    }

    private ObjectNode readProfile() {
        try {
            Path path = directory.resolve("credentials.json");
            requireProtectedFile(path);
            JsonNode raw = mapper.readTree(Files.readString(path));
            if (!(raw instanceof ObjectNode profile)) throw new IllegalArgumentException();
            for (String key : new String[]{"client_id", "access_token", "refresh_token", "subject", "saved_at"}) {
                if (profile.path(key).asText().isBlank()) throw new IllegalArgumentException();
            }
            if ("dynamic_agent_client".equals(profile.path("client_id").asText())
                    || !"https://auth.openai.com".equals(profile.path("issuer").asText())
                    || !"Bearer".equalsIgnoreCase(profile.path("token_type").asText())
                    || profile.path("expires_in").asLong() <= 0) throw new IllegalArgumentException();
            requirePlanScope(profile);
            expiresAt(profile);
            return profile;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("未找到有效的 ChatGPT 授权，请运行本机授权助手并安全导入 credentials.json");
        }
    }

    private void requirePlanScope(JsonNode profile) {
        for (JsonNode scope : profile.path("scopes")) if (PLAN_SCOPE.equals(scope.asText())) return;
        throw new IllegalStateException("尚未授予 ChatGPT 订阅使用权限，请重新授权");
    }

    private Instant expiresAt(JsonNode profile) {
        return Instant.parse(profile.path("saved_at").asText()).plusSeconds(profile.path("expires_in").asLong());
    }

    private JsonNode tokenRequest(String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ChatGPT 授权请求失败（HTTP " + response.statusCode()
                        + "），请检查网络或重新授权");
            }
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("ChatGPT 授权服务连接失败，请稍后重试");
        }
    }

    private void prepareDirectory() throws Exception {
        if (Files.isSymbolicLink(directory)) throw new IllegalStateException("授权目录不能是符号链接");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void requireProtectedFile(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 64 * 1024) throw new IllegalArgumentException();
        var permissions = Files.getPosixFilePermissions(path);
        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
            throw new IllegalStateException("ChatGPT 授权文件权限过宽，请设为 0600");
        }
    }

    private void writeProtected(Path target, JsonNode value) {
        Path temp = null;
        try {
            prepareDirectory();
            temp = Files.createTempFile(directory, ".credential-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(temp, mapper.writeValueAsString(value));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存 ChatGPT 授权，请检查运行目录权限");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { }
        }
    }

    public static String safeError(Throwable e) {
        return e instanceof IllegalStateException ? e.getMessage() : "ChatGPT 连接失败，请检查网络或重新授权";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
