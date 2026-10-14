package io.codex.haloaiassistant.chatgpt;

import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** Metadata stays ready; real connection warming is limited to two minutes after page/user activity. */
@Component
@Slf4j
public class ChatGptModelWarmup {
    static final long LEASE_NANOS = Duration.ofMinutes(2).toNanos();
    static final long TRANSPORT_INTERVAL = Duration.ofSeconds(20).toNanos();
    static final long METADATA_INTERVAL = Duration.ofMinutes(1).toNanos();
    private final ChatGptResponsesClient responses;
    private final ReactiveSettingFetcher settings;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean active;
    private long leaseUntil;
    private long lastTransport = -1;
    private long lastMetadata = -1;
    private Disposable task;

    @Autowired
    public ChatGptModelWarmup(ChatGptResponsesClient responses, ReactiveSettingFetcher settings) {
        this(responses, settings, System::nanoTime);
    }

    ChatGptModelWarmup(ChatGptResponsesClient responses, ReactiveSettingFetcher settings, LongSupplier clock) {
        this.responses = responses;
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized void start() {
        active = true;
        lastMetadata = -1;
        trigger();
    }

    public synchronized void stop() {
        active = false;
        leaseUntil = 0;
        if (task != null) task.dispose();
        responses.invalidateModels();
    }

    public synchronized void pageOpened() {
        if (!active) return;
        leaseUntil = clock.getAsLong() + LEASE_NANOS;
        trigger();
    }

    public synchronized void endTransportLease() {
        leaseUntil = 0;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public synchronized void trigger() {
        long now = clock.getAsLong();
        boolean transport = now < leaseUntil && (lastTransport < 0 || now - lastTransport >= TRANSPORT_INTERVAL);
        boolean metadata = lastMetadata < 0 || now - lastMetadata >= METADATA_INTERVAL;
        if (!active || (!transport && !metadata) || !running.compareAndSet(false, true)) return;
        if (transport) lastTransport = now;
        lastMetadata = now;
        task = settings.fetch("basic", AiAssistantSetting.class)
                .filter(setting -> "chatgpt".equals(setting.getProvider()))
                .flatMap(setting -> transport ? responses.prewarmTransport() : responses.prewarmModels())
                .doOnNext(value -> {
                    if (transport) log.info("chatgpt_transport_ready elapsedMs={}",
                            (clock.getAsLong() - now) / 1_000_000);
                })
                .doOnError(error -> log.warn("chatgpt_warmup_failed type={}", error.getClass().getSimpleName()))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> running.set(false))
                .subscribe();
    }
}
