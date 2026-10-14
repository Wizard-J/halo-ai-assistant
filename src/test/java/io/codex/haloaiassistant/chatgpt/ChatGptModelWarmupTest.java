package io.codex.haloaiassistant.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.plugin.ReactiveSettingFetcher;

class ChatGptModelWarmupTest {
    @Test
    void startupWarmsOnlySubscriptionMetadataAndStopDisablesFurtherWork() {
        var settings = mock(ReactiveSettingFetcher.class);
        var responses = mock(ChatGptResponsesClient.class);
        var setting = new AiAssistantSetting();
        setting.setProvider("chatgpt");
        when(settings.fetch("basic", AiAssistantSetting.class)).thenReturn(Mono.just(setting));
        when(responses.prewarmModels()).thenReturn(Mono.just(new ObjectMapper().createArrayNode()));
        var warmup = new ChatGptModelWarmup(responses, settings);
        warmup.trigger();
        verifyNoInteractions(settings, responses);
        warmup.start();
        verify(responses).prewarmModels();
        warmup.stop();
        warmup.trigger();
        verify(responses).invalidateModels();
        verifyNoMoreInteractions(responses);
    }

    @Test
    void apiProviderNeverWarmsSubscriptionAndFailureDoesNotPreventLaterRetry() {
        var settings = mock(ReactiveSettingFetcher.class);
        var responses = mock(ChatGptResponsesClient.class);
        var setting = new AiAssistantSetting();
        setting.setProvider("api");
        when(settings.fetch("basic", AiAssistantSetting.class)).thenReturn(Mono.just(setting));
        var warmup = new ChatGptModelWarmup(responses, settings);
        warmup.start();
        verifyNoInteractions(responses);
        setting.setProvider("chatgpt");
        when(responses.prewarmModels()).thenReturn(Mono.error(new IllegalStateException("temporary failure")),
                Mono.just(new ObjectMapper().createArrayNode()));
        assertDoesNotThrow(warmup::trigger);
        assertDoesNotThrow(warmup::trigger);
        verify(responses, times(2)).prewarmModels();
    }

    @Test
    void pageAndScheduledTriggersSharePendingWarmupWithoutBlockingCaller() {
        var settings = mock(ReactiveSettingFetcher.class);
        var responses = mock(ChatGptResponsesClient.class);
        var setting = new AiAssistantSetting();
        setting.setProvider("chatgpt");
        when(settings.fetch("basic", AiAssistantSetting.class)).thenReturn(Mono.just(setting));
        var pending = Sinks.<com.fasterxml.jackson.databind.node.ArrayNode>one();
        when(responses.prewarmModels()).thenReturn(pending.asMono());
        var warmup = new ChatGptModelWarmup(responses, settings);
        warmup.start();
        warmup.trigger();
        warmup.trigger();
        verify(responses).prewarmModels();
        pending.tryEmitValue(new ObjectMapper().createArrayNode());
        warmup.trigger();
        verify(responses, times(2)).prewarmModels();
        warmup.stop();
    }
}
