package io.codex.haloaiassistant.chatgpt;

import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** Keeps metadata and its shared connection ready without generating text or touching conversations. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatGptModelWarmup {
    private final ChatGptResponsesClient responses;
    private final ReactiveSettingFetcher settings;
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean active;
    private Disposable task;

    public synchronized void start() {
        active = true;
        trigger();
    }

    public synchronized void stop() {
        active = false;
        if (task != null) task.dispose();
        responses.invalidateModels();
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    public synchronized void trigger() {
        if (!active || !running.compareAndSet(false, true)) return;
        task = settings.fetch("basic", AiAssistantSetting.class)
                .filter(setting -> "chatgpt".equals(setting.getProvider()))
                .flatMap(setting -> responses.prewarmModels())
                .doOnError(error -> log.warn("chatgpt_warmup_failed type={}", error.getClass().getSimpleName()))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> running.set(false))
                .subscribe();
    }
}
