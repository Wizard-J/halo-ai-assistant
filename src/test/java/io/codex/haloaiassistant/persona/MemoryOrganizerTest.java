package io.codex.haloaiassistant.persona;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class MemoryOrganizerTest {
 final ObjectMapper json = new ObjectMapper();
 ObjectNode state(int n) {
  ObjectNode state = json.createObjectNode(); ArrayNode entries = state.putArray("entries");
  for(int i=0;i<n;i++)entries.addObject().put("id","id"+i).put("kind","fact").put("text","记忆编号"+i+"："+"内容".repeat(30))
   .put("evidence","原话"+i).put("conversation","c").put("sourceIndex",i).put("sourceTime","2026-10-14T01:00:00Z");
  return state;
 }
 @Test void capacityArchivesEveryOriginalAndKeepsImportantFacts() {
  var original=state(145);original.withArray("entries").addObject().put("id","priority").put("kind","constraint").put("text","严禁删除原始记录").put("evidence","严禁删除原始记录");
  var copy=original.deepCopy();var plan=MemoryOrganizer.organize(original);
  assertEquals(copy,original);assertEquals(146,plan.originals().size());
  assertTrue(plan.state().path("entries").size()<=80);assertTrue(MemoryLogic.usedChars(plan.state())<=8000);
  assertEquals("priority",plan.state().path("entries").get(0).path("id").asText());
  assertFalse(MemoryOrganizer.needed(plan.state()));
 }
 @Test void exactDuplicatesMergeAllProvenanceWithoutInventingText() {
  var original=state(1);var duplicate=original.withArray("entries").get(0).deepCopy();((ObjectNode)duplicate).put("conversation","second").put("id","other");original.withArray("entries").add(duplicate);
  assertTrue(MemoryOrganizer.needed(original));var plan=MemoryOrganizer.organize(original);
  assertEquals(1,plan.state().path("entries").size());assertEquals(2,plan.state().path("entries").get(0).path("sources").size());
  var combined=MemoryOrganizer.combine(List.of(plan.originals(),plan.state().withArray("entries"),plan.originals()));
  assertEquals(1,combined.size());assertEquals(2,combined.get(0).path("sources").size());
 }
 @Test void punctuationAndOppositeDecisionsAreNeverMerged() {
  var s=state(0);s.withArray("entries").addObject().put("kind","fact").put("text","比例1.2");s.withArray("entries").addObject().put("kind","fact").put("text","比例12");
  assertEquals(2,MemoryOrganizer.combine(List.of(s.withArray("entries"))).size());
 }
 @Test void archiveDetailRetrievalStaysBoundedAndFindsFactsOutsideActiveSet() {
  var s=state(145);((ObjectNode)s.withArray("entries").get(144)).put("text","我给新项目起的名字是星河蓝鹊");
  var plan=MemoryOrganizer.organize(s);String context=MemoryOrganizer.context(plan.state(),plan.originals(),"新项目起的名字是什么？");
  assertTrue(context.contains("星河蓝鹊"));assertTrue(context.length()<12500);
  assertFalse(MemoryLogic.promptMemory(plan.state()).contains("星河蓝鹊"));
 }
 @Test void expiredTodosRemainInSnapshotWhileLeavingActivePrompt() {
  var s=state(1);s.withArray("entries").addObject().put("kind","todo").put("text","旧临时事项").put("sourceTime","2020-01-01T00:00:00Z");
  var plan=MemoryOrganizer.organize(s);assertEquals(2,plan.originals().size());assertEquals(1,plan.state().path("entries").size());
 }
}
