package io.codex.haloaiassistant.endpoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import java.security.Principal;
import java.time.Duration;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

class AiChatEndpointTest {

    @Test
    void missingOrConsumedConfirmationStillReturnsAResponse() {
        var endpoint = new AiChatEndpoint(null, null, null, null, null, null, null,
                new PendingActionService());
        ServerRequest request = mock(ServerRequest.class);
        when(request.pathVariable("id")).thenReturn("confirm_consumed");
        doReturn(Mono.just((Principal) () -> "admin")).when(request).principal();
        var response = endpoint.handleConfirmAction(request).block(Duration.ofSeconds(5));
        assertNotNull(response);
        assertTrue(response.statusCode().is2xxSuccessful());
    }

    @Test
    void pageIconOnlyAcceptsBoundedRasterDataUrls() {
        assertTrue(AiChatEndpoint.isSafePageIcon("data:image/png;base64,iVBORw0KGgo="));
        assertTrue(AiChatEndpoint.isSafePageIcon("data:image/webp;base64,UklGRg=="));
        assertTrue(AiChatEndpoint.isSafePageIcon("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="));
        assertFalse(AiChatEndpoint.isSafePageIcon("ri-sparkling-2-fill"));
        assertFalse(AiChatEndpoint.isSafePageIcon("data:image/svg+xml,<svg onload=alert(1)></svg>"));
        assertFalse(AiChatEndpoint.isSafePageIcon("javascript:alert(1)"));
    }
}
