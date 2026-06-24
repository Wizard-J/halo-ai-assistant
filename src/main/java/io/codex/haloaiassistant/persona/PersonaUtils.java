package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persona 相关纯函数工具（无 Halo API 依赖，可独立测试）。
 */
public final class PersonaUtils {

    private PersonaUtils() {}

    private static final double CHINESE_CHARS_PER_TOKEN = 1.5;
    private static final double ASCII_CHARS_PER_TOKEN = 3.5;

    /** 估算文本 token 数 */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int chinese = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) chinese++;
            else if (!Character.isWhitespace(c)) ascii++;
        }
        return (int) Math.ceil(chinese / CHINESE_CHARS_PER_TOKEN + ascii / ASCII_CHARS_PER_TOKEN);
    }

    /** 估算消息数组总 token 数 */
    public static int estimateConversationTokens(ArrayNode messages, int systemPromptTokens) {
        int total = systemPromptTokens;
        if (messages != null) {
            for (JsonNode msg : messages) {
                total += estimateTokens(msg.path("content").asText());
            }
        }
        return total;
    }

    /** 判断是否应该压缩 */
    public static boolean shouldCompress(int estimatedTokens, int modelWindow, double triggerRatio) {
        return estimatedTokens >= (int) (modelWindow * triggerRatio);
    }

    /** 压缩对话：保留最后 N 轮，返回压缩后的 ArrayNode */
    public static ArrayNode compressMessages(ArrayNode messages, int reservedRounds, String archiveNote) {
        ObjectMapper mapper = new ObjectMapper();
        java.util.List<JsonNode> retainedList = new java.util.ArrayList<>();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < reservedRounds * 2; i--) {
            retainedList.add(0, messages.get(i));
            count++;
        }
        int pruned = messages.size() - retainedList.size();
        if (pruned > 0 && archiveNote != null && !archiveNote.isBlank()) {
            ObjectNode systemNote = mapper.createObjectNode();
            systemNote.put("role", "system");
            systemNote.put("content", archiveNote);
            retainedList.add(0, systemNote);
        }
        ArrayNode retained = mapper.createArrayNode();
        for (JsonNode node : retainedList) retained.add(node);
        return retained;
    }
}
