package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class TagTool implements Tool {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "listTags";
    }

    @Override
    public String getDescription() {
        return "获取所有文章标签列表";
    }

    @Override
    public String getParametersJsonSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema.toPrettyString();
    }

    @Override
    public String execute(JsonNode args) {
        try {
            ListResult<Tag> result = client.list(Tag.class, null, null, 0, 100).block();
            if (result == null || result.getItems().isEmpty()) {
                return "暂无标签";
            }

            StringBuilder sb = new StringBuilder("🏷️ 标签列表：\n\n");
            sb.append("| 名称 | 别名 | 文章数 |\n|---|---|---|\n");
            for (Tag tag : result.getItems()) {
                var meta = tag.getMetadata();
                var spec = tag.getSpec();
                String name = spec.getDisplayName() != null ? spec.getDisplayName() : meta.getName();
                String slug = spec.getSlug() != null ? spec.getSlug() : "-";
                var status = tag.getStatus();
                int count = status != null && status.getPostCount() != null ? status.getPostCount() : 0;
                sb.append(String.format("| %s | %s | %d |\n", name, slug, count));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取标签失败", e);
            return "获取标签失败: " + e.getMessage();
        }
    }

    @Component
    public static class CreateTagTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public CreateTagTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "createTag";
        }

        @Override
        public String getDescription() {
            return "创建新的文章标签";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");
            nameProp.put("description", "标签名称");
            schema.putArray("required").add("name");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String name = args.get("name").asText();
            String slug = name.toLowerCase().replace(" ", "-");

            try {
                Tag tag = new Tag();
                var metadata = new run.halo.app.extension.Metadata();
                metadata.setName("tag-" + Instant.now().toEpochMilli());
                metadata.setGenerateName("tag-");
                tag.setMetadata(metadata);
                var spec = new Tag.TagSpec();
                spec.setDisplayName(name);
                spec.setSlug(slug);
                tag.setSpec(spec);

                client.create(tag).block();
                return "标签创建成功：\n- 名称：" + name + "\n- 别名：" + slug;
            } catch (Exception e) {
                log.error("创建标签失败", e);
                return "创建标签失败: " + e.getMessage();
            }
        }
    }
}
