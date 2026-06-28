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
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.Comparator;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommentTool implements Tool {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "listComments";
    }

    @Override
    public String getDescription() {
        return "获取最新评论列表，支持分页";
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
            ListResult<Comment> result = client.list(Comment.class, null, null, page - 1, 10).block();
            if (result == null || result.getItems().isEmpty()) {
                return "暂无评论";
            }

            StringBuilder sb = new StringBuilder("💬 评论列表（第 " + page + " 页）：\n\n");
            for (Comment comment : result.getItems()) {
                var spec = comment.getSpec();
                var meta = comment.getMetadata();
                String author = spec.getOwner() != null ? spec.getOwner().getDisplayName() : "匿名";
                String content = spec.getContent() != null && spec.getContent().length() > 60
                        ? spec.getContent().substring(0, 60) + "..." : spec.getContent();
                String time = meta.getCreationTimestamp() != null
                        ? meta.getCreationTimestamp().toString().substring(0, 16) : "未知";
                String approved = spec.getApproved() != null && spec.getApproved() ? "✅" : "⏳";

                sb.append(String.format("%s [%s] %s: %s (%s)\n",
                        approved, meta.getName(), author, content, time));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取评论失败", e);
            return "获取评论失败: " + e.getMessage();
        }
    }

    @Component
    public static class ApproveCommentTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public ApproveCommentTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "approveComment";
        }

        @Override
        public String getDescription() {
            return "审核通过指定评论";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "评论 ID");
            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.get("id").asText();

            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String summary = "将审核通过评论（ID: " + id + "）。";
                var result = pas.create("approveComment", "审核评论确认", summary, RiskLevel.MEDIUM, args);
                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** 审核评论确认\n"
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
    public static class DeleteCommentTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public DeleteCommentTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "deleteComment";
        }

        @Override
        public String getDescription() {
            return "删除指定评论";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "评论 ID");
            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.get("id").asText();

            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String summary = "将删除评论（ID: " + id + "）。";
                var result = pas.create("deleteComment", "删除评论确认", summary, RiskLevel.MEDIUM, args);
                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** 删除评论确认\n"
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
