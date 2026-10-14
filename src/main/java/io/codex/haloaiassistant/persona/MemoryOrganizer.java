package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Lossless organization: originals live in immutable snapshots; no AI rewrites facts. */
public final class MemoryOrganizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int ACTIVE_CHARS = 8000;
    public static final int ACTIVE_ITEMS = 80;
    private MemoryOrganizer() { }
    public record Plan(ObjectNode state, ArrayNode originals, String fingerprint) { }
    private static String key(JsonNode entry) {
        return entry.path("kind").asText() + ":" + entry.path("text").asText()
                .strip().replaceAll("\\s+", " ");
    }
    public static ArrayNode combine(Collection<? extends JsonNode> collections) {
        Map<String, ObjectNode> grouped = new LinkedHashMap<>();
        for (JsonNode entries : collections) for (JsonNode item : entries) {
            if (!item.isObject() || item.path("text").asText().isBlank()) continue;
            ObjectNode group = grouped.computeIfAbsent(key(item), unused -> {
                ObjectNode copy = item.deepCopy(); copy.remove("sources"); copy.putArray("sources"); return copy;
            });
            ArrayNode sources = group.withArray("sources");
            JsonNode input = item.path("sources").isArray() ? item.path("sources") : JSON.createArrayNode().add(item);
            for (JsonNode source : input) {
                ObjectNode copy = source.deepCopy(); copy.remove("sources");
                boolean duplicate = false;
                for (JsonNode known : sources) if (known.equals(copy)) duplicate = true;
                if (!duplicate) sources.add(copy);
            }
        }
        ArrayNode result = JSON.createArrayNode(); grouped.values().forEach(result::add); return result;
    }
    public static boolean needed(ObjectNode state) {
        JsonNode entries = state.path("entries");
        if (!entries.isArray() || entries.isEmpty()) return false;
        if (entries.size() > 100 || MemoryLogic.usedChars(state) > 10000) return true;
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : entries) if (!MemoryLogic.retainable(entry) || !seen.add(key(entry))) return true;
        return false;
    }
    private static int importance(JsonNode entry) {
        return switch (entry.path("kind").asText()) {
            case "constraint" -> 5; case "preference" -> 4; case "decision" -> 3; case "fact" -> 2; default -> 1;
        };
    }
    public static Plan organize(ObjectNode candidate) {
        ObjectNode next = candidate.deepCopy();
        ArrayNode originals = candidate.withArray("entries").deepCopy();
        List<JsonNode> ordered = new ArrayList<>();
        for (JsonNode item : combine(List.of(originals))) if (MemoryLogic.retainable(item)) ordered.add(item);
        ordered.sort(Comparator.<JsonNode>comparingInt(MemoryOrganizer::importance).reversed()
                .thenComparing(e -> e.path("sourceTime").asText(), Comparator.reverseOrder()));
        ArrayNode active = JSON.createArrayNode();
        for (JsonNode item : ordered) {
            if (active.size() == ACTIVE_ITEMS) break;
            ObjectNode live = item.deepCopy();
            ArrayNode sources = live.withArray("sources");
            live.put("sourceCount", sources.size());
            while (sources.size() > 8) sources.remove(sources.size()-1);
            active.add(live);
            ObjectNode probe = JSON.createObjectNode().set("entries", active);
            if (MemoryLogic.usedChars(probe) > ACTIVE_CHARS) active.remove(active.size() - 1);
        }
        next.set("entries", active);
        return new Plan(next, originals, MemoryLogic.hash(originals.toString()));
    }
    public static final class Index {
        private final Map<String, List<JsonNode>> postings = new HashMap<>();
        public Index(ArrayNode library) {
            // Newest source wins ties; originals remain in immutable snapshots regardless of index limits.
            List<JsonNode> sorted = new ArrayList<>(); library.forEach(sorted::add);
            sorted.sort(Comparator.comparing(e -> e.path("sourceTime").asText(), Comparator.reverseOrder()));
            for (JsonNode original : sorted) {
                ObjectNode entry = JSON.createObjectNode();
                for (String field : List.of("kind", "text", "sourceTime", "evidence")) entry.set(field, original.path(field));
                String text = entry.path("text").asText().toLowerCase(Locale.ROOT);
                Set<String> seen = new HashSet<>();
                for (int i = 0; i + 1 < text.length(); i++) {
                    String term = text.substring(i, i + 2);
                    if (!seen.add(term)) continue;
                    List<JsonNode> matches = postings.computeIfAbsent(term, unused -> new ArrayList<>());
                    if (matches.size() < 128) matches.add(entry);
                }
            }
        }
        Collection<JsonNode> candidates(Set<String> terms) {
            Map<String, JsonNode> result = new LinkedHashMap<>();
            for (String term : new TreeSet<>(terms)) for (JsonNode item : postings.getOrDefault(term, List.of())) {
                result.putIfAbsent(key(item), item);
                if (result.size() >= 512) return result.values();
            }
            return result.values();
        }
    }
    public static String context(ObjectNode state, ArrayNode library, String question) {
        return context(state, library == null ? null : new Index(library), question);
    }
    /** Matching runs on a warmed, bounded local index; never issues a model/network request. */
    public static String context(ObjectNode state, Index index, String question) {
        if (index == null || question == null || question.isBlank()) return MemoryLogic.promptMemory(state);
        Set<String> terms = new HashSet<>();
        String normalized = question.substring(0, Math.min(128, question.length())).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        for (int i = 0; i + 1 < normalized.length(); i++) terms.add(normalized.substring(i, i + 2));
        terms.removeAll(Set.of("什么", "之前", "一下", "帮我", "这个", "那个", "如何", "可以", "我的", "记得"));
        List<JsonNode> relevant = new ArrayList<>();
        for (JsonNode item : index.candidates(terms)) if (MemoryLogic.retainable(item) && score(item, terms) > 0) relevant.add(item);
        relevant.sort(Comparator.<JsonNode>comparingInt(e -> score(e, terms)).reversed()
                .thenComparing(e -> e.path("sourceTime").asText(), Comparator.reverseOrder()));
        ArrayNode selected = JSON.createArrayNode(); Set<String> seen = new HashSet<>();
        // Relevant archive details get a bounded share; active important facts retain the rest.
        for (JsonNode item : relevant) {
            if (selected.size() >= 20) break;
            if (!seen.add(key(item))) continue;
            selected.add(item);
            if (MemoryLogic.usedChars(JSON.createObjectNode().set("entries", selected)) > 3500) selected.remove(selected.size()-1);
        }
        for (JsonNode item : state.path("entries")) {
            if (!seen.add(key(item))) continue;
            selected.add(item);
            if (MemoryLogic.usedChars(JSON.createObjectNode().set("entries", selected)) > MemoryLogic.MEMORY_CHARS)
                selected.remove(selected.size()-1);
        }
        return MemoryLogic.promptMemory(JSON.createObjectNode().set("entries", selected));
    }
    private static int score(JsonNode item, Set<String> terms) {
        String text = item.path("text").asText().toLowerCase(Locale.ROOT);
        int score = 0; for (String term : terms) if (text.contains(term)) score++;
        return score;
    }
}
