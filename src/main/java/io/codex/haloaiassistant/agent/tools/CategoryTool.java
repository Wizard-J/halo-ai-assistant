package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import io.codex.haloaiassistant.agent.confirmation.RiskLevel;
import io.codex.haloaiassistant.agent.confirmation.SpringContextBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryTool implements Tool {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "listCategories";
    }

    @Override
    public String getDescription() {
        return "获取所有文章分类列表";
    }

    @Override
    public String getParametersJsonSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pageProp = props.putObject("page");
        pageProp.put("type", "integer");
        pageProp.put("description", "页码");
        pageProp.put("default", 1);
        return schema.toPrettyString();
    }

    @Override
    public String execute(JsonNode args) {
        int page = args.has("page") ? args.get("page").asInt(1) : 1;
        try {
            ListResult<Category> result = client.list(Category.class, null, null, page - 1, 20).block();
            if (result == null || result.getItems().isEmpty()) {
                return "暂无分类";
            }

            StringBuilder sb = new StringBuilder("📂 文章分类列表：\n\n");
            sb.append("| 名称 | 别名 | 文章数 |\n|---|---|---|\n");
            for (Category cat : result.getItems()) {
                var meta = cat.getMetadata();
                var spec = cat.getSpec();
                String name = spec.getDisplayName() != null ? spec.getDisplayName() : meta.getName();
                String slug = spec.getSlug() != null ? spec.getSlug() : "-";
                var status = cat.getStatus();
                int count = status != null && status.getPostCount() != null ? status.getPostCount() : 0;
                sb.append(String.format("| %s | %s | %d |\n", name, slug, count));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取分类失败", e);
            return "获取分类失败: " + e.getMessage();
        }
    }

    @Component
    public static class CreateCategoryTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public CreateCategoryTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "createCategory";
        }

        @Override
        public String getDescription() {
            return "创建新的文章分类";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode nameProp = props.putObject("name");
            nameProp.put("type", "string");
            nameProp.put("description", "分类名称");
            ObjectNode slugProp = props.putObject("slug");
            slugProp.put("type", "string");
            slugProp.put("description", "分类别名（URL 中使用）");
            schema.putArray("required").add("name");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String name = args.get("name").asText();
            String slug = args.has("slug") ? args.get("slug").asText() : name.toLowerCase().replace(" ", "-");

            // 创建分类需要确认
            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String summary = "将创建分类「" + name + "」。";
                var result = pas.create("createCategory", "创建分类确认", summary, RiskLevel.MEDIUM, args);
                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** 创建分类确认\n"
                        + "**摘要：** " + summary + "\n"
                        + "**风险等级：** MEDIUM\n\n"
                        + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                        + "请管理员点击确认后执行操作。";
            } catch (Exception e) {
                log.error("创建待确认操作失败，已取消执行", e);
                return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
            }
        }
    }

    @Component
    public static class DeleteCategoryTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public DeleteCategoryTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "deleteCategory";
        }

        @Override
        public String getDescription() {
            return "删除指定分类";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "分类 ID");
            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.get("id").asText();

            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String summary = "将删除分类（ID: " + id + "）。";
                var result = pas.create("deleteCategory", "删除分类确认", summary, RiskLevel.MEDIUM, args);
                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** 删除分类确认\n"
                        + "**摘要：** " + summary + "\n"
                        + "**风险等级：** MEDIUM\n\n"
                        + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                        + "请管理员点击确认后执行操作。";
            } catch (Exception e) {
                log.error("创建待确认操作失败，已取消执行", e);
                return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
            }
        }
    }
}
