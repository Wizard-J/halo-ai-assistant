package io.codex.haloaiassistant.endpoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiChatEndpointTest {

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
