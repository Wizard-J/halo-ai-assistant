package io.codex.haloaiassistant.persona;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 对话引用索引 —— 绕过 Halo 扩展索引系统。
 * 这是一个全新的扩展类型（kind="ConvRef"），Halo 从未注册过，
 * 会强制创建带 metadata.name 索引的新 scheme。
 * 每个记录对应一个对话，包含 sessionId + personaId + conversationName。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "ai-assistant.plugin.halo.run",
     version = "v1alpha1",
     kind = "ConvRef",
     plural = "convrefs",
     singular = "convref")
public class ConversationRef extends AbstractExtension {

    private ConvRefSpec spec;

    @Data
    public static class ConvRefSpec {
        /** 浏览器会话标识 */
        private String sessionId;

        /** 关联的 Persona ID */
        private String personaId;

        /** 对应的 Conversation 资源名称 */
        private String conversationName;

        /** 更新时间 */
        private java.time.Instant updatedAt;
    }
}
