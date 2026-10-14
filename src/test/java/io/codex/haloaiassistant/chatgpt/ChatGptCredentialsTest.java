package io.codex.haloaiassistant.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptCredentialsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void privateProxyIsOptInAndRejectsUnsafeConfiguration() throws Exception {
        var store = new ChatGptCredentials(directory);
        var direct = store.httpClient();
        assertTrue(direct.proxy().isEmpty());
        assertSame(direct, store.httpClient());
        Path proxy = directory.resolve("proxy.json");
        Files.writeString(proxy, "{\"host\":\"halo-chatgpt-proxy\",\"port\":7890}");
        Files.setPosixFilePermissions(proxy, PosixFilePermissions.fromString("rw-------"));
        var proxied = store.httpClient();
        assertNotSame(direct, proxied);
        assertSame(proxied, store.httpClient());
        var selected = proxied.proxy().orElseThrow().select(java.net.URI.create("https://auth.openai.com"));
        assertEquals(7890, ((java.net.InetSocketAddress) selected.getFirst().address()).getPort());
        Files.setPosixFilePermissions(proxy, PosixFilePermissions.fromString("rw-r--r--"));
        var error = assertThrows(IllegalStateException.class, store::httpClient);
        assertFalse(error.getMessage().contains("halo-chatgpt-proxy"));
        Files.setPosixFilePermissions(proxy, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(proxy, "{\"host\":\"user:secret@host\",\"port\":7890}");
        assertThrows(IllegalStateException.class, store::httpClient);
        Files.writeString(proxy, "");
        assertThrows(IllegalStateException.class, store::httpClient);
        store.destroy();
        assertTrue(direct.awaitTermination(java.time.Duration.ofSeconds(2)));
        assertTrue(proxied.awaitTermination(java.time.Duration.ofSeconds(2)));
    }

    @Test
    void missingCredentialsAreReportedWithoutLeakingPaths() {
        var store = new ChatGptCredentials(directory);
        var status = store.status();
        assertEquals(false, status.get("connected"));
        assertFalse(status.toString().contains(directory.toString()));
    }

    @Test
    void keepsRuntimeHostSeparateFromImportedLaptopAndNeverExposesTokens() throws Exception {
        ObjectNode profile = profile();
        profile.put("ext_agent_host_id", "urn:uuid:laptop");
        save(profile);
        var store = new ChatGptCredentials(directory);
        String host = store.hostId();
        assertNotEquals("urn:uuid:laptop", host);
        assertEquals(host, new ChatGptCredentials(directory).hostId());
        assertEquals("sensitive-access", store.accessToken());
        assertEquals(true, store.status().get("connected"));
        assertFalse(store.status().toString().contains("sensitive"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("host.json")));
    }

    @Test
    void rejectsOverbroadPermissionsAndIdentityOnlyScope() throws Exception {
        save(profile());
        Files.setPosixFilePermissions(directory.resolve("credentials.json"), PosixFilePermissions.fromString("rw-r--r--"));
        var store = new ChatGptCredentials(directory);
        assertEquals(false, store.status().get("connected"));
        ObjectNode identityOnly = profile(); identityOnly.putArray("scopes").add("openid");
        save(identityOnly);
        assertEquals(false, store.status().get("connected"));
        assertThrows(IllegalStateException.class, store::accessToken);
    }

    @Test
    void refusesSymbolicCredentialFileAndDynamicClientId() throws Exception {
        Path other = directory.resolve("other.json");
        Files.writeString(other, profile().toString());
        Files.createSymbolicLink(directory.resolve("credentials.json"), other);
        assertEquals(false, new ChatGptCredentials(directory).status().get("connected"));
        Files.delete(directory.resolve("credentials.json"));
        save(profile().put("client_id", "dynamic_agent_client"));
        assertEquals(false, new ChatGptCredentials(directory).status().get("connected"));
    }

    @Test
    void concurrentRefreshRotatesOnceAndPersistsNewCredentialAtomically() throws Exception {
        save(profile().put("saved_at", Instant.now().minusSeconds(3600).toString()));
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"access_token\":\"rotated-access\",\"refresh_token\":\"rotated-refresh\",\"expires_in\":3600}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        var store = new ChatGptCredentials(directory, http);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = IntStream.range(0, 12).mapToObj(i -> (Callable<String>) store::accessToken).toList();
            for (var result : pool.invokeAll(calls)) assertEquals("rotated-access", result.get());
        }
        verify(http, times(1)).send(any(), any());
        JsonNode saved = mapper.readTree(Files.readString(directory.resolve("credentials.json")));
        assertEquals("rotated-refresh", saved.path("refresh_token").asText());
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.resolve("credentials.json")));
    }

    @Test
    void failedRefreshKeepsOriginalCredentialsAndNeverEchoesResponseBody() throws Exception {
        ObjectNode expired = profile().put("saved_at", Instant.now().minusSeconds(3600).toString());
        save(expired);
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("sensitive-refresh");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        var store = new ChatGptCredentials(directory, http);
        Exception error = assertThrows(IllegalStateException.class, store::accessToken);
        assertFalse(error.getMessage().contains("sensitive"));
        assertEquals(expired, mapper.readTree(Files.readString(directory.resolve("credentials.json"))));
    }

    private ObjectNode profile() {
        ObjectNode profile = mapper.createObjectNode().put("client_id", "oaiapp_test")
                .put("access_token", "sensitive-access").put("refresh_token", "sensitive-refresh")
                .put("issuer", "https://auth.openai.com").put("subject", "user")
                .put("token_type", "Bearer").put("expires_in", 3600).put("saved_at", Instant.now().toString());
        profile.putArray("scopes").add(ChatGptCredentials.PLAN_SCOPE);
        return profile;
    }

    private void save(ObjectNode profile) throws Exception {
        Path path = directory.resolve("credentials.json");
        Files.writeString(path, profile.toString());
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
}
