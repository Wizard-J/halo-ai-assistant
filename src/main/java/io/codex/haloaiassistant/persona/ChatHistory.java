package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.codex.haloaiassistant.agent.AgentService.ChatMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded model view. Never rewrites or deletes the stored transcript. */
public final class ChatHistory {
    static final int MAX_MESSAGES = 40;
    static final int MAX_CHARS = 48000;

    private ChatHistory() { }

    public static List<ChatMessage> context(ArrayNode stored, JsonNode fallback) {
        JsonNode source = stored != null && !stored.isEmpty() ? stored : fallback;
        List<ChatMessage> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int chars = 0;
        boolean truncated = false;
        if (source != null && source.isArray()) {
            // Work backwards so legacy replays do not displace the newest actual turn.
            for (int i = source.size() - 1; i >= 0 && result.size() < MAX_MESSAGES; i--) {
                JsonNode message = source.get(i);
                String role = message.path("role").asText();
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                String content = message.path("content").asText();
                String timestamp = message.path("timestamp").asText();
                // Identical text at different times is a legitimate repeated message.
                String identity = timestamp + "\u0000" + role + "\u0000" + content;
                if (!timestamp.isBlank() && !seen.add(identity)) continue;
                if (chars + content.length() > MAX_CHARS) {
                    truncated = true;
                    if (result.isEmpty()) result.add(new ChatMessage(role,
                            content.substring(content.length() - MAX_CHARS)));
                    break;
                }
                result.addFirst(new ChatMessage(role, content));
                chars += content.length();
            }
            if (truncated || source.size() > result.size()) result.addFirst(new ChatMessage("system",
                    "本轮仅载入最近的对话上下文；完整历史仍保存在聊天记录中。不要声称已阅读未载入的早期消息。"));
        }
        return result;
    }
}
