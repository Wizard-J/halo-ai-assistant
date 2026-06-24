package io.codex.haloaiassistant.config;

import io.codex.haloaiassistant.agent.Tool;
import io.codex.haloaiassistant.agent.ToolRegistry;
import io.codex.haloaiassistant.endpoint.AiChatEndpoint;
import io.codex.haloaiassistant.persona.PersonaController;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class PluginConfiguration {

    private final ToolRegistry toolRegistry;
    private final List<Tool> tools;
    private final AiChatEndpoint chatEndpoint;
    private final PersonaController personaController;

    @PostConstruct
    public void init() {
        for (Tool tool : tools) {
            toolRegistry.register(tool);
            log.info("已注册工具: {}", tool.getName());
        }
        log.info("AI 智能助手已加载 {} 个工具", tools.size());
    }

    @Bean
    RouterFunction<ServerResponse> aiChatRouter() {
        // 合并 Persona 管理和对话的路由
        return chatEndpoint.endpoint()
                .and(personaController.endpoints());
    }
}
