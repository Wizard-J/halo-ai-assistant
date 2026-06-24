package io.codex.haloaiassistant;

import io.codex.haloaiassistant.persona.Conversation;
import io.codex.haloaiassistant.persona.PersonaDefinition;
import io.codex.haloaiassistant.persona.PersonaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Slf4j
@Component
public class HaloAiAssistantPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final PersonaService personaService;

    public HaloAiAssistantPlugin(PluginContext pluginContext,
                                  SchemeManager schemeManager,
                                  PersonaService personaService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.personaService = personaService;
    }

    @Override
    public void start() {
        // 注册自定义扩展
        schemeManager.register(PersonaDefinition.class);
        schemeManager.register(Conversation.class);
        log.info("已注册自定义扩展: PersonaDefinition, Conversation");

        // 初始化内置 Persona
        personaService.initBuiltinPersonas()
                .doOnSuccess(v -> log.info("已初始化内置 Persona"))
                .doOnError(e -> log.error("初始化内置 Persona 失败", e))
                .subscribe();

        log.info("AI 智能助手插件已启动");
    }

    @Override
    public void stop() {
        log.info("AI 智能助手插件已停止");
    }
}
