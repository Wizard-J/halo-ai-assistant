package io.codex.haloaiassistant.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.plugin.ReactiveSettingFetcher;

class ChatGptModelWarmupTest {
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final ReactiveSettingFetcher settings = mock(ReactiveSettingFetcher.class);
    final ChatGptResponsesClient responses = mock(ChatGptResponsesClient.class);
    final AiAssistantSetting setting = new AiAssistantSetting();
    ChatGptModelWarmup configured(String provider) {
        setting.setProvider(provider);
        when(settings.fetch("basic", AiAssistantSetting.class)).thenReturn(Mono.just(setting));
        lenient().when(responses.prewarmModels()).thenReturn(Mono.just(new ObjectMapper().createArrayNode()));
        lenient().when(responses.prewarmTransport()).thenReturn(Mono.just(new ObjectMapper().createArrayNode()));
        return new ChatGptModelWarmup(responses, settings, clock::get);
    }
    void advance(int seconds) { clock.addAndGet(java.time.Duration.ofSeconds(seconds).toNanos()); }

    @Test
    void startupWarmsMetadataButDoesNotStartAnUnboundedTransportLease() {
        var warmup = configured("chatgpt");
        warmup.trigger();
        verifyNoInteractions(settings, responses);
        warmup.start();
        verify(responses).prewarmModels();
        warmup.trigger();
        verifyNoMoreInteractions(responses);
        advance(60);warmup.trigger();
        verify(responses, times(2)).prewarmModels();
        verify(responses, never()).prewarmTransport();
        warmup.stop();advance(60);warmup.trigger();
        verify(responses).invalidateModels();
        verifyNoMoreInteractions(responses);
    }

    @Test
    void pageActivityForcesRealTransportAndCapsRequestRateAndLeaseLifetime() {
        var warmup = configured("chatgpt");warmup.start();
        warmup.pageOpened();warmup.pageOpened();warmup.trigger();
        verify(responses).prewarmTransport();
        advance(19);warmup.trigger();verify(responses).prewarmTransport();
        advance(1);warmup.trigger();verify(responses, times(2)).prewarmTransport();
        for (int i=0;i<4;i++) { advance(20);warmup.trigger(); }
        verify(responses, times(6)).prewarmTransport();
        advance(20);warmup.trigger(); // lease ends exactly at 120s
        verify(responses, times(6)).prewarmTransport();
        advance(60);warmup.trigger();
        verify(responses, times(6)).prewarmTransport();
        warmup.pageOpened();verify(responses, times(7)).prewarmTransport();
        warmup.endTransportLease();advance(20);warmup.trigger();
        verify(responses, times(7)).prewarmTransport();
    }

    @Test
    void apiProviderSkipsSubscriptionAndFailureHasBoundedRetry() {
        var warmup = configured("api");warmup.start();warmup.pageOpened();verifyNoInteractions(responses);
        setting.setProvider("chatgpt");advance(20);
        when(responses.prewarmTransport()).thenReturn(Mono.error(new IllegalStateException("temporary")),
                Mono.just(new ObjectMapper().createArrayNode()));
        assertDoesNotThrow(warmup::trigger);warmup.pageOpened();
        verify(responses).prewarmTransport();
        advance(20);assertDoesNotThrow(warmup::trigger);
        verify(responses,times(2)).prewarmTransport();
    }

    @Test
    void pendingRequestIsSharedAndStopPreventsAnyFurtherLeaseWork() {
        var warmup=configured("chatgpt");warmup.start();
        var pending=Sinks.<com.fasterxml.jackson.databind.node.ArrayNode>one();
        when(responses.prewarmTransport()).thenReturn(pending.asMono());
        warmup.pageOpened();advance(20);warmup.trigger();warmup.pageOpened();
        verify(responses).prewarmTransport();
        pending.tryEmitValue(new ObjectMapper().createArrayNode());
        warmup.trigger();verify(responses,times(2)).prewarmTransport();
        warmup.stop();advance(20);warmup.pageOpened();warmup.trigger();
        verify(responses,times(2)).prewarmTransport();
        verify(responses).invalidateModels();
    }
}
