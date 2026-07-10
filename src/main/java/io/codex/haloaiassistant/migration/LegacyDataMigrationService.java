package io.codex.haloaiassistant.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;

/**
 * One-time, non-destructive migration from the private {@code ai-assistant} plugin identity.
 * The old resources are only read. New resources are created or updated in the
 * {@code wizardj-ai-assistant} namespace, and a marker is written after all copies succeed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyDataMigrationService {

    static final String OLD_GROUP = "ai-assistant.plugin.halo.run";
    static final String NEW_GROUP = "wizardj-ai-assistant.plugin.halo.run";
    static final String VERSION = "v1alpha1";
    static final String MARKER_NAME = "wizardj-ai-assistant-migration-state";

    private static final GroupVersionKind OLD_PERSONA =
            new GroupVersionKind(OLD_GROUP, VERSION, "PersonaDefinition");
    private static final GroupVersionKind NEW_PERSONA =
            new GroupVersionKind(NEW_GROUP, VERSION, "PersonaDefinition");
    private static final GroupVersionKind OLD_CONVERSATION =
            new GroupVersionKind(OLD_GROUP, VERSION, "Conversation");
    private static final GroupVersionKind NEW_CONVERSATION =
            new GroupVersionKind(NEW_GROUP, VERSION, "Conversation");
    private static final GroupVersionKind OLD_CONV_REF =
            new GroupVersionKind(OLD_GROUP, VERSION, "ConvRef");
    private static final GroupVersionKind NEW_CONV_REF =
            new GroupVersionKind(NEW_GROUP, VERSION, "ConvRef");

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(initialDelay = 20_000, fixedDelay = 60_000)
    public void migrateWhenAvailable() {
        if (!running.compareAndSet(false, true)) return;
        Mono.fromCallable(this::migrateBlocking)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.warn("旧版 AI 助手数据迁移尚未完成，将稍后重试: {}",
                        rootMessage(error)))
                .doFinally(signal -> running.set(false))
                .subscribe();
    }

    MigrationReport migrateBlocking() {
        if (client.fetch(ConfigMap.class, MARKER_NAME).block() != null) {
            return new MigrationReport(true, 0, 0, 0, 0);
        }
        if (client.fetch(ConfigMap.class, "ai-assistant-configmap").block() == null
                && client.fetch(ConfigMap.class, "ai-assistant-auto-ops-state").block() == null) {
            return new MigrationReport(false, 0, 0, 0, 0);
        }

        int settings = copyConfigMap("ai-assistant-configmap", "wizardj-ai-assistant-configmap");
        int autoOps = copyConfigMap("ai-assistant-auto-ops-state",
                "wizardj-ai-assistant-auto-ops-state");
        int personas = copyExtensions(OLD_PERSONA, NEW_PERSONA);
        int conversations = copyExtensions(OLD_CONVERSATION, NEW_CONVERSATION)
                + copyExtensions(OLD_CONV_REF, NEW_CONV_REF);

        ConfigMap marker = new ConfigMap();
        Metadata metadata = new Metadata();
        metadata.setName(MARKER_NAME);
        marker.setMetadata(metadata);
        marker.setData(new HashMap<>(Map.of(
                "status", "completed",
                "sourcePlugin", "ai-assistant",
                "targetPlugin", "wizardj-ai-assistant",
                "migratedAt", Instant.now().toString(),
                "settings", String.valueOf(settings),
                "autoOpsState", String.valueOf(autoOps),
                "personas", String.valueOf(personas),
                "conversations", String.valueOf(conversations))));
        client.create(marker).block();

        MigrationReport report = new MigrationReport(false, settings, autoOps, personas, conversations);
        log.info("旧版 AI 助手数据迁移完成: settings={}, autoOps={}, personas={}, conversations={}",
                settings, autoOps, personas, conversations);
        return report;
    }

    private int copyConfigMap(String oldName, String newName) {
        ConfigMap source = client.fetch(ConfigMap.class, oldName).block();
        if (source == null) return 0;
        ConfigMap target = client.fetch(ConfigMap.class, newName).block();
        Map<String, String> copied = source.getData() == null
                ? new HashMap<>() : new HashMap<>(source.getData());
        if (target == null) {
            target = new ConfigMap();
            Metadata metadata = new Metadata();
            metadata.setName(newName);
            target.setMetadata(metadata);
            target.setData(copied);
            client.create(target).block();
        } else {
            target.setData(copied);
            client.update(target).block();
        }
        return 1;
    }

    private int copyExtensions(GroupVersionKind oldGvk, GroupVersionKind newGvk) {
        List<String> names;
        try {
            names = client.indexedQueryEngine()
                    .retrieveAll(oldGvk, new ListOptions(), Sort.unsorted());
        } catch (Exception e) {
            log.debug("旧版扩展类型不存在，跳过: {}", oldGvk);
            return 0;
        }
        int copied = 0;
        for (String name : names) {
            Unstructured source = client.fetch(oldGvk, name).block();
            if (source == null || source.getMetadata().getDeletionTimestamp() != null) continue;
            Unstructured target = transform(source, newGvk);
            Unstructured existing = client.fetch(newGvk, name).block();
            if (existing == null) {
                client.create(target).block();
            } else {
                target.getMetadata().setVersion(existing.getMetadata().getVersion());
                client.update(target).block();
            }
            copied++;
        }
        return copied;
    }

    Unstructured transform(Unstructured source, GroupVersionKind targetGvk) {
        Map<String, Object> data = objectMapper.convertValue(source.getData(),
                new TypeReference<Map<String, Object>>() { });
        data.put("apiVersion", targetGvk.group() + "/" + targetGvk.version());
        data.put("kind", targetGvk.kind());
        Map<String, Object> oldMetadata = castMap(data.get("metadata"));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", source.getMetadata().getName());
        copyIfPresent(oldMetadata, metadata, "labels");
        copyIfPresent(oldMetadata, metadata, "annotations");
        data.put("metadata", metadata);
        return new Unstructured(data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target,
                                      String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record MigrationReport(boolean alreadyCompleted, int settings, int autoOpsState,
                           int personas, int conversations) { }
}
