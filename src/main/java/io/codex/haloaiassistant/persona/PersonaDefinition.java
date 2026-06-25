package io.codex.haloaiassistant.persona;

import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Persona 定义 —— 代表一个 AI 助手的"灵魂"。
 * 通过上传 SKILL.md 创建，存储在 Halo 自定义资源中。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "ai-assistant.plugin.halo.run",
     version = "v1alpha2",
     kind = "PersonaDefinition",
     plural = "personadefinitions",
     singular = "personadefinition")
public class PersonaDefinition extends AbstractExtension {

    private PersonaSpec spec;

    @Data
    public static class PersonaSpec {
        /** 显示名称，如"芒格视角" */
        private String displayName;

        /** 简介，来自 SKILL.md 的 description */
        private String description;

        /** 头像图片 URL */
        private String iconUrl;

        /** 品牌色，如 #1E3A5F */
        private String brandColor;

        /** 完整的 system prompt（从 SKILL.md 解析组装） */
        private String systemPrompt;

        /** 欢迎语 */
        private String greeting;

        /** 上传的上下文内容（如 AGENTS.md），附加到 system prompt */
        private String contextContent;

        /** 思考/等待时的短语列表（JSON 数组），如：["摘下眼镜…","擦拭眼镜…"] */
        private String thinkingPhrases;

        /** 是否为系统内置（不可删除） */
        private boolean builtin;

        /** 创建时间 */
        private Instant createdAt;

        /** 更新时间 */
        private Instant updatedAt;
    }
}
