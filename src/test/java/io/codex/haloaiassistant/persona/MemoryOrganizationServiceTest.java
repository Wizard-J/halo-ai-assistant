package io.codex.haloaiassistant.persona;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.*;
import run.halo.app.plugin.ReactiveSettingFetcher;

class MemoryOrganizationServiceTest {
 final ObjectMapper json=new ObjectMapper();
 final ReactiveExtensionClient client=mock(ReactiveExtensionClient.class);
 final PersonaService personas=mock(PersonaService.class);
 final AgentService agent=mock(AgentService.class);
 final ReactiveSettingFetcher settings=mock(ReactiveSettingFetcher.class);
 final Map<String,ConfigMap> db=new ConcurrentHashMap<>();
 PersonaMemoryService service;ConversationRef source;ArrayNode messages;String scope;
 ConfigMap cloneMap(ConfigMap m){return json.convertValue(m,ConfigMap.class);}
 @BeforeEach void setup(){
  service=new PersonaMemoryService(client,personas,agent,settings);scope=PersonaMemoryService.name("user-u","p");
  source=new ConversationRef();var metadata=new Metadata();metadata.setName("c");source.setMetadata(metadata);
  var spec=new ConversationRef.ConvRefSpec();spec.setSessionId("user-u");spec.setPersonaId("p");source.setSpec(spec);
  messages=json.createArrayNode();for(int i=0;i<44;i++)messages.addObject().put("role",i%2==0?"user":"assistant").put("content",i==0?"我偏好简洁中文":"对话"+i).put("id","m"+i);
  when(personas.parseMessages(any())).thenAnswer(i->messages.deepCopy());
  when(personas.getPersona("p")).thenReturn(Mono.just(new PersonaDefinition()));
  when(personas.listConversations("user-u","p")).thenReturn(Mono.just(List.of(source)));
  when(personas.getConversationByGVK("c")).thenReturn(Mono.just(source));
  when(settings.fetch("basic",AiAssistantSetting.class)).thenReturn(Mono.just(new AiAssistantSetting()));
  when(agent.generateText(any(),anyString(),anyString(),anyInt())).thenReturn(Mono.just("{\"facts\":[{\"kind\":\"preference\",\"text\":\"偏好简洁中文\",\"sourceIndex\":0,\"evidence\":\"我偏好简洁中文\"}]}"));
  when(client.fetch(eq(ConfigMap.class),anyString())).thenAnswer(i->Mono.defer(()->Mono.justOrEmpty(db.get(i.getArgument(1))).map(this::cloneMap)));
  when(client.create(any(ConfigMap.class))).thenAnswer(i->Mono.fromSupplier(()->{
   ConfigMap m=cloneMap(i.getArgument(0));m.getMetadata().setVersion(1L);
   if(db.putIfAbsent(m.getMetadata().getName(),m)!=null)throw new org.springframework.dao.DuplicateKeyException("exists");return cloneMap(m);
  }));
  when(client.update(any(ConfigMap.class))).thenAnswer(i->Mono.fromSupplier(()->{
   ConfigMap m=cloneMap(i.getArgument(0));var old=db.get(m.getMetadata().getName());
   if(!m.getMetadata().getVersion().equals(old.getMetadata().getVersion()))throw new org.springframework.dao.OptimisticLockingFailureException("conflict");
   m.getMetadata().setVersion(old.getMetadata().getVersion()+1);db.put(m.getMetadata().getName(),m);return cloneMap(m);
  }));
  var full=json.createObjectNode();var entries=full.putArray("entries");
  for(int i=0;i<129;i++)entries.addObject().put("id","f"+i).put("kind","fact").put("text",i==128?"新项目名称星河蓝鹊":"重要信息编号"+i)
    .put("evidence","原话"+i).put("sourceIndex",i).put("conversation","original").put("sourceTime","2026-10-14T00:00:00Z");
  ConfigMap map=new ConfigMap();var meta=new Metadata();meta.setName(scope);meta.setVersion(1L);map.setMetadata(meta);map.setData(new HashMap<>(Map.of("state",full.toString())));db.put(scope,map);
 }
 @Test void overflowContinuesWithLosslessSnapshotAndCachedRecall() throws Exception {
  var before=service.decode(db.get(scope)).withArray("entries").deepCopy();
  assertTrue(service.process(source).block(Duration.ofSeconds(5)));
  var state=service.status("user-u","p").block();assertEquals(4,state.path("cursors").path("c").path("end").asInt());
  assertTrue(state.path("entries").size()<=80);assertEquals(1,state.path("archives").size());
  String archive=state.path("archives").get(0).path("name").asText();var preserved=service.archive("user-u","p",archive).block().withArray("entries");
  assertEquals(130,preserved.size());for(var entry:before)assertTrue(preserved.findValuesAsText("id").contains(entry.path("id").asText()));
  long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();String context="";
  while(System.nanoTime()<end){context=service.memoryFor("user-u","p","新项目名称是什么？").block();if(context.contains("星河蓝鹊"))break;Thread.sleep(10);}
  assertTrue(context.contains("星河蓝鹊"));
  when(client.fetch(eq(ConfigMap.class),anyString())).thenReturn(Mono.never());
  assertTrue(service.memoryFor("user-u","p","新项目名称是什么？").block(Duration.ofMillis(100)).contains("星河蓝鹊"));
  service.stop();
 }
 @Test void archiveFailureDoesNotLoseFactsOrAdvanceCursor() {
  var before=service.decode(db.get(scope)).withArray("entries").deepCopy();
  when(client.create(any(ConfigMap.class))).thenReturn(Mono.error(new IllegalStateException("storage failure")));
  assertTrue(service.process(source).block());var state=service.status("user-u","p").block();
  assertEquals(before,state.path("entries"));assertTrue(state.path("cursors").isMissingNode());assertEquals("retry",state.path("status").asText());
  assertEquals(1,db.size());service.stop();
 }
 @Test void maintenanceWithoutNewChatUsesNoModelAndRestartDoesNotReorganize() {
  messages.removeAll();messages.addObject().put("role","user").put("content","short");
  assertTrue(service.process(source).block());var state=service.status("user-u","p").block();assertEquals(1,state.path("organizationCount").asInt());
  var restarted=new PersonaMemoryService(client,personas,agent,settings);assertFalse(restarted.process(source).block());
  verifyNoInteractions(agent);assertEquals(2,db.size());service.stop();restarted.stop();
 }
 @Test void archiveAccessCannotCrossUserOrPersona() {
  assertTrue(service.process(source).block());var state=service.status("user-u","p").block();String archive=state.path("archives").get(0).path("name").asText();
  assertThrows(IllegalArgumentException.class,()->service.archive("user-other","p",archive).block());
  assertThrows(IllegalArgumentException.class,()->service.archive("user-u","other",archive).block());service.stop();
 }
 @Test void commitConflictReusesOneSnapshotAndPreservesConcurrentFields() {
  var saves=new java.util.concurrent.atomic.AtomicInteger();
  when(client.update(any(ConfigMap.class))).thenAnswer(i->Mono.fromSupplier(()->{
   ConfigMap map=cloneMap(i.getArgument(0));
   if(saves.incrementAndGet()==2){
    var latest=cloneMap(db.get(scope));var state=service.decode(latest);state.put("sibling","preserved");
    latest.getData().put("state",state.toString());latest.getMetadata().setVersion(latest.getMetadata().getVersion()+1);db.put(scope,latest);
    throw new org.springframework.dao.OptimisticLockingFailureException("conflict");
   }
   map.getMetadata().setVersion(db.get(scope).getMetadata().getVersion()+1);db.put(scope,map);return cloneMap(map);
  }));
  assertTrue(service.process(source).block(Duration.ofSeconds(5)));
  var state=service.status("user-u","p").block();assertEquals("preserved",state.path("sibling").asText());
  assertEquals(1,state.path("organizationCount").asInt());assertEquals(2,db.size());
  verify(agent,times(1)).generateText(any(),anyString(),anyString(),anyInt());
  assertFalse(service.process(source).block());assertEquals(2,db.size());service.stop();
 }

}
