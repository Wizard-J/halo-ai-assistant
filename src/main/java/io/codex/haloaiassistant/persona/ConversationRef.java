package io.codex.haloaiassistant.persona;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 对话记录 —— 直接存储在 ConvRef 扩展中，绕过 Conversation 的索引缺失问题。
 * ConvRef 是全新的扩展 kind，索引正常运行。
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

        /** 对话标题 */
        private String title;

        /** 消息列表 JSON */
        private String messages;

        /** 创建时间（ISO-8601 字符串，避免插件运行时 Jackson JavaTime 绑定问题） */
        private String createdAt;

        /** 更新时间（ISO-8601 字符串，避免插件运行时 Jackson JavaTime 绑定问题） */
        private String updatedAt;

        /** 是否已压缩 */
        private boolean compressed;

        /** 压缩后的历史摘要 */
        private String summary;

        /** 已提炼的消息条数 */
        private int refinedMessageCount;
    }
}
