package io.codex.haloaiassistant.persona;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 对话记录 —— 按 sessionId + personaId 隔离的对话历史。
 * 存储在 Halo 自定义资源中，自动过期清理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "ai-assistant.plugin.halo.run",
     version = "v1alpha1",
     kind = "Conversation",
     plural = "conversations",
     singular = "conversation")
public class Conversation extends AbstractExtension {

    private ConversationSpec spec;

    @Data
    public static class ConversationSpec {
        /** 浏览器会话标识 */
        private String sessionId;

        /** 关联的 Persona ID */
        private String personaId;

        /** 对话标题（首条用户消息的前42字符） */
        private String title;

        /** 消息列表 JSON */
        private ArrayNode messages;

        /** 创建时间 */
        private Instant createdAt;

        /** 更新时间 */
        private Instant updatedAt;

        /** 是否已压缩 */
        private boolean compressed;

        /** 压缩后的历史摘要 */
        private String summary;
    }
}
