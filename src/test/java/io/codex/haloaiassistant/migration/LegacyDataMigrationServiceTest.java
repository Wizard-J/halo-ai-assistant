package io.codex.haloaiassistant.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Unstructured;

class LegacyDataMigrationServiceTest {

    @Test
    void transformChangesNamespaceAndDropsServerMetadata() {
        var service = new LegacyDataMigrationService(null);
        Map<String, Object> data = new HashMap<>();
        data.put("apiVersion", "ai-assistant.plugin.halo.run/v1alpha1");
        data.put("kind", "ConvRef");
        data.put("metadata", new HashMap<>(Map.of(
                "name", "conv-1", "version", 9, "creationTimestamp", Instant.now().toString(),
                "labels", Map.of("example", "true"))));
        data.put("spec", Map.of("sessionId", "user-wizardj", "messages", "[]"));
        Unstructured source = new Unstructured(data);
        Metadata metadata = new Metadata();
        metadata.setName("conv-1");
        source.setMetadata(metadata);

        Unstructured result = service.transform(source,
                new GroupVersionKind("wizardj-ai-assistant.plugin.halo.run", "v1alpha1", "ConvRef"));

        assertEquals("wizardj-ai-assistant.plugin.halo.run/v1alpha1", result.getApiVersion());
        assertEquals("ConvRef", result.getKind());
        assertEquals("conv-1", result.getMetadata().getName());
        assertFalse(result.getData().toString().contains("creationTimestamp"));
        assertFalse(result.getData().toString().contains("version=9"));
        assertEquals("[]", Unstructured.getNestedValue(result.getData(), "spec", "messages").orElseThrow());
    }
}
