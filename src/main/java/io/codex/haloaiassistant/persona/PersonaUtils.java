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

    /** 构建 AI 提炼 prompt（保留原文 + 对话记录，要求 AI 合并） */
    public static String buildRefinePrompt(String existingContext, String history) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下内容合并提炼成一份完整的 AGENTS.md：\n\n");

        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("=== 现有 AGENTS.md ===\n").append(existingContext).append("\n\n");
        }

        boolean hasHistory = history != null && !history.isBlank();
        if (hasHistory) {
            sb.append("=== 新增对话 ===\n").append(history).append("\n\n");
        }

        sb.append("请按以下要求操作：\n");
        sb.append("1. 将现有 AGENTS.md 与新增的内容合并\n");
        sb.append("2. 不得删除或修改现有 AGENTS.md 中的任何原有内容\n");
        sb.append("3. 将新增信息插入到对应章节中\n");
        sb.append("4. 如果没有合适章节，在末尾新建章节\n");
        sb.append("5. **只返回完整的、更新后的 AGENTS.md 文档**，不要有任何额外说明\n");
        sb.append("6. 保持 Markdown 格式与原有文档一致\n");
        return sb.toString();
    }

    public static String buildExportContent(String contextContent, String historyStr) {
        if (contextContent != null && !contextContent.isBlank()) {
            if (historyStr != null && !historyStr.isBlank()) {
                return contextContent + "\n\n---\n\n## 对话导出\n\n" + historyStr.trim() + "\n";
            }
            return contextContent;
        }
        if (historyStr != null && !historyStr.isBlank()) {
            return "# 对话导出\n\n" + historyStr.trim() + "\n";
        }
        return "# (无内容)\n\n尚未上传上下文，也没有对话记录。\n";
}

}
