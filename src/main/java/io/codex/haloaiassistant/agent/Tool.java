package io.codex.haloaiassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 工具接口 - 每个工具对应一个 function calling 能力
 */
public interface Tool {

    /**
     * 工具名称，会被注册为 function calling 的 function name
     */
    String getName();

    /**
     * 工具描述，帮助 AI 理解何时调用此工具
     */
    String getDescription();

    /**
     * 工具的 JSON Schema 参数定义
     */
    String getParametersJsonSchema();

    /**
     * 执行工具逻辑
     * @param args 从 AI 解析出的参数
     * @return 执行结果（Markdown 格式文本）
     */
    String execute(JsonNode args);
}
