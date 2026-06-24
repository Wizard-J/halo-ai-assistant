package io.codex.haloaiassistant;

import io.codex.haloaiassistant.persona.Conversation;
import io.codex.haloaiassistant.persona.PersonaDefinition;
import io.codex.haloaiassistant.persona.PersonaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
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
        // Halo 2.22.x 中 schemeManager.register(Class) 的简写方式可能不会自动创建
        // 扩展索引，导致后续 client.fetch()/update() 操作报错:
        // "No indices found for type"。
        // 因此使用 register(Class, Consumer<IndexSpecs>) 显式注册索引。
        registerPersonaDefinitionWithIndex();
        schemeManager.register(Conversation.class);
        log.info("已注册自定义扩展: PersonaDefinition, Conversation");

        // 初始化内置 Persona
        personaService.initBuiltinPersonas()
                .doOnSuccess(v -> log.info("已初始化内置 Persona"))
                .doOnError(e -> log.error("初始化内置 Persona 失败", e))
                .subscribe();

        log.info("AI 智能助手插件已启动");
    }

    /**
     * 注册 PersonaDefinition 扩展，并显式创建 metadata.name 索引，
     * 确保 client.fetch()/update()/delete() 能正常使用。
     */
    private void registerPersonaDefinitionWithIndex() {
        try {
            schemeManager.register(PersonaDefinition.class, indexSpecs -> {
                // metadata.name 是主键索引，Client.fetch/update/delete 依赖此索引
                indexSpecs.add(
                    IndexSpecs.<PersonaDefinition, String>single(
                            "metadata.name", String.class)
                        .indexFunc(p -> p.getMetadata().getName())
                        .unique(true)
                        .nullable(false)
                        .build()
                );
            });
        } catch (Exception e) {
            log.warn("显式索引注册 PersonaDefinition 失败，回退到基础注册", e);
            try {
                schemeManager.register(PersonaDefinition.class);
            } catch (Exception e2) {
                log.error("PersonaDefinition 注册完全失败", e2);
            }
        }
    }

    @Override
    public void stop() {
        log.info("AI 智能助手插件已停止");
    }
}
