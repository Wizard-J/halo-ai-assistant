package io.codex.haloaiassistant;

import io.codex.haloaiassistant.config.AiAssistantSetting;
import io.codex.haloaiassistant.agent.AgentService;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.agent.tools.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Slf4j
@Component
public class HaloAiAssistantPlugin extends BasePlugin {

    public HaloAiAssistantPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("AI 智能助手插件已启动");
    }

    @Override
    public void stop() {
        log.info("AI 智能助手插件已停止");
    }
}
