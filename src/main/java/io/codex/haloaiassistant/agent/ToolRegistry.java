package io.codex.haloaiassistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final List<Tool> tools = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void register(Tool tool) {
        tools.add(tool);
    }

    public Optional<Tool> getByName(String name) {
        return tools.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst();
    }

    public List<Tool> getAll() {
        return List.copyOf(tools);
    }

    /**
     * 生成 OpenAI/DeepSeek function calling 格式的工具声明
     */
    public String generateToolsJson() {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (Tool tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");
            ObjectNode functionNode = toolNode.putObject("function");
            functionNode.put("name", tool.getName());
            functionNode.put("description", tool.getDescription());
            try {
                functionNode.set("parameters", objectMapper.readTree(tool.getParametersJsonSchema()));
            } catch (Exception e) {
                functionNode.putObject("parameters");
            }
            toolsArray.add(toolNode);
        }
        return toolsArray.toPrettyString();
    }
}
