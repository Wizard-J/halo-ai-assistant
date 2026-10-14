package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

class PersonaMemoryServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final ReactiveExtensionClient store = mock(ReactiveExtensionClient.class);
    final PersonaService personas = mock(PersonaService.class);
    final AgentService agent = mock(AgentService.class);
    final ReactiveSettingFetcher settings = mock(ReactiveSettingFetcher.class);
    final AtomicReference<ConfigMap> durable = new AtomicReference<>();
    ConversationRef source;
    ArrayNode messages;
    PersonaMemoryService service;
    ConfigMap copy(ConfigMap map) { return json.convertValue(map, ConfigMap.class); }
    @BeforeEach void setup() {
        source = new ConversationRef(); var meta = new Metadata(); meta.setName("conv-test"); source.setMetadata(meta);
        var spec = new ConversationRef.ConvRefSpec(); spec.setSessionId("user-u"); spec.setPersonaId("p"); source.setSpec(spec);
        messages = json.createArrayNode();
        for (int i=0;i<44;i++) messages.addObject().put("id", "m"+i).put("role",i%2==0?"user":"assistant")
                .put("content", i==0?"我偏好简洁中文":"msg"+i);
        spec.setMessages(messages.toString());
        when(personas.listConversations("user-u", "p")).thenReturn(Mono.just(java.util.List.of(source)));
        when(personas.getPersona("p")).thenReturn(Mono.just(new PersonaDefinition()));
        when(personas.parseMessages(any())).thenAnswer(i -> messages.deepCopy());
        when(personas.getConversationByGVK("conv-test")).thenReturn(Mono.just(source));
        when(settings.fetch("basic", AiAssistantSetting.class)).thenReturn(Mono.just(new AiAssistantSetting()));
        when(agent.generateText(any(), anyString(), anyString(), eq(2500))).thenReturn(Mono.just("""
          {"facts":[{"kind":"preference","text":"偏好简洁中文","sourceIndex":0,"evidence":"我偏好简洁中文"}]}
          """));
        when(store.fetch(eq(ConfigMap.class), anyString())).thenAnswer(i -> Mono.defer(() -> Mono.justOrEmpty(durable.get()).map(this::copy)));
        when(store.create(any(ConfigMap.class))).thenAnswer(i -> Mono.defer(() -> {
            if (durable.get()!=null) return Mono.error(new org.springframework.dao.DuplicateKeyException("exists"));
            var map=copy(i.getArgument(0)); map.getMetadata().setVersion(1L); durable.set(map); return Mono.just(copy(map));
        }));
        when(store.update(any(ConfigMap.class))).thenAnswer(i -> Mono.defer(() -> {
            var map=copy(i.getArgument(0));
            if (!map.getMetadata().getVersion().equals(durable.get().getMetadata().getVersion()))
                return Mono.error(new org.springframework.dao.OptimisticLockingFailureException("conflict"));
            map.getMetadata().setVersion(map.getMetadata().getVersion()+1); durable.set(map); return Mono.just(copy(map));
        }));
        service = new PersonaMemoryService(store,personas,agent,settings);
    }
    @Test void restartDoesNotReextractOrChangeOriginalRecords() {
        String original = source.getSpec().getMessages();
        assertTrue(service.process(source).block());
        var state = service.status("user-u","p").block();
        assertEquals(4,state.path("cursors").path("conv-test").path("end").asInt());
        assertEquals(1,state.path("entries").size());
        assertTrue(service.memoryFor("user-u","p").block().contains("偏好简洁中文"));
        var restarted = new PersonaMemoryService(store,personas,agent,settings);
        assertFalse(restarted.process(source).block());
        assertEquals(original, source.getSpec().getMessages());
        verify(agent,times(1)).generateText(any(),anyString(),anyString(),anyInt());
        verify(store,never()).update(any(run.halo.app.extension.Unstructured.class));
    }
    @Test void foregroundCancelsGenerationAndLeavesCursorPending() {
        var response = Sinks.<String>one();
        AtomicInteger cancelled = new AtomicInteger();
        when(agent.generateText(any(),anyString(),anyString(),anyInt())).thenReturn(response.asMono().doOnCancel(cancelled::incrementAndGet));
        var task=service.process(source).subscribe();
        assertEquals("processing",service.status("user-u","p").block().path("status").asText());
        var foreground=service.foreground(Flux.never()).subscribe();
        assertEquals(1,cancelled.get());
        var state=service.status("user-u","p").block();
        assertEquals("pending",state.path("status").asText()); assertTrue(state.path("cursors").isMissingNode());
        assertFalse(service.canWork()); foreground.dispose(); assertFalse(service.canWork()); task.dispose();
    }
    @Test void failedModelOutputRetriesWithoutAdvancingCursor() {
        when(agent.generateText(any(),anyString(),anyString(),anyInt())).thenReturn(Mono.just("not json"));
        assertTrue(service.process(source).block());
        var state=service.status("user-u","p").block(); assertEquals("retry",state.path("status").asText());
        assertTrue(state.path("cursors").isMissingNode()); assertFalse(service.process(source).block());
        verify(agent,times(1)).generateText(any(),anyString(),anyString(),anyInt());
    }
    @Test void optimisticRetryMergesFreshStateWithoutRepeatingModelCall() {
        AtomicInteger saves=new AtomicInteger();
        when(store.update(any(ConfigMap.class))).thenAnswer(i -> Mono.defer(() -> {
            if (saves.incrementAndGet()==1) {
                var latest=copy(durable.get()); var state=service.decode(latest);
                state.put("unrelated","preserved"); latest.getData().put("state",state.toString());
                latest.getMetadata().setVersion(2L);durable.set(latest);
                return Mono.error(new org.springframework.dao.OptimisticLockingFailureException("conflict"));
            }
            var map=copy(i.getArgument(0));map.getMetadata().setVersion(3L);durable.set(map);return Mono.just(copy(map));
        }));
        assertTrue(service.process(source).block(Duration.ofSeconds(5)));
        assertEquals("preserved",service.status("user-u","p").block().path("unrelated").asText());
        verify(agent,times(1)).generateText(any(),anyString(),anyString(),anyInt());
    }
    @Test void sourceChangeAfterExtractionFailsClosed() {
        when(agent.generateText(any(),anyString(),anyString(),anyInt())).thenReturn(Mono.fromSupplier(() -> {
            ((ObjectNode)messages.get(0)).put("content","concurrent edit");
            return "{\"facts\":[]}";
        }));
        assertTrue(service.process(source).block());
        assertTrue(service.status("user-u","p").block().path("cursors").isMissingNode());
    }
    @Test void cachedMemoryDoesNotWaitForWorkerOrStorage() {
        service.memoryFor("user-u","p").block();
        when(store.fetch(eq(ConfigMap.class),anyString())).thenReturn(Mono.never());
        StepVerifier.create(service.memoryFor("user-u","p")).expectNext("").verifyComplete();
    }
    @Test void activeLeasePreventsDuplicateExtractionInAnotherWorker() {
        var waiting=Sinks.<String>one();when(agent.generateText(any(),anyString(),anyString(),anyInt())).thenReturn(waiting.asMono());
        var task=service.process(source).subscribe();
        var another=new PersonaMemoryService(store,personas,agent,settings);
        assertFalse(another.process(source).block());
        verify(agent,times(1)).generateText(any(),anyString(),anyString(),anyInt());
        task.dispose();service.stop();
    }
}
