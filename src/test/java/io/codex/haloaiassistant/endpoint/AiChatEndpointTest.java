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
    void chatPersonaWithoutSessionStillUsesRestrictedToolsAndAuthenticatedSession() {
        var settings = mock(run.halo.app.plugin.ReactiveSettingFetcher.class);
        when(settings.fetch("basic", io.codex.haloaiassistant.config.AiAssistantSetting.class))
                .thenReturn(Mono.just(new io.codex.haloaiassistant.config.AiAssistantSetting()));
        var agent = mock(io.codex.haloaiassistant.agent.AgentService.class);
        var personas = mock(io.codex.haloaiassistant.persona.PersonaService.class);
        var persona = new io.codex.haloaiassistant.persona.PersonaDefinition();
        var spec = new io.codex.haloaiassistant.persona.PersonaDefinition.PersonaSpec();
        spec.setSystemPrompt("chat only");
        persona.setSpec(spec);
        when(personas.getPersona("munger")).thenReturn(Mono.just(persona));
        when(personas.getOrCreateConversation("user-admin", "munger"))
                .thenReturn(Mono.just(new io.codex.haloaiassistant.persona.ConversationRef()));
        when(personas.parseMessages(any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
        when(agent.chatWithTools(anyList(), anySet(), eq("admin")))
                .thenReturn(reactor.core.publisher.Flux.just("chat reply"));
        var endpoint = new AiChatEndpoint(null, settings, agent, null, null, null, personas, null);
        ServerRequest request = mock(ServerRequest.class);
        when(request.bodyToMono(String.class)).thenReturn(Mono.just("{\"message\":\"create article\",\"persona\":\"munger\"}"));
        assertTrue(endpoint.handleAuthenticatedChat(request, "admin").block().statusCode().is2xxSuccessful());
        verify(agent).chatWithTools(anyList(), eq(java.util.Set.of("webSearch", "searchSiteArticles")), eq("admin"));
        verify(agent, never()).chat(anyList(), anyString());
        verify(personas).getOrCreateConversation("user-admin", "munger");
    }

    @Test
    void chatPersonasCannotListOrConfirmManagementActions() {
        var pending = mock(PendingActionService.class);
        var endpoint = new AiChatEndpoint(null, null, null, null, null, null, null, pending);
        ServerRequest request = mock(ServerRequest.class);
        when(request.queryParam("persona")).thenReturn(java.util.Optional.of("munger"));
        assertTrue(endpoint.handleListPendingActions(request).block().statusCode().is2xxSuccessful());
        assertTrue(endpoint.handleConfirmAction(request).block().statusCode().value() == 403);
        verifyNoInteractions(pending);
    }

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
