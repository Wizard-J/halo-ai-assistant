package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
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
                Comment comment = client.get(Comment.class, id).block();
                if (comment == null) {
                    return "评论不存在: " + id;
                }
                comment.getSpec().setApproved(true);
                client.update(comment).block();
                return "评论已审核通过（ID: " + id + "）";
            } catch (Exception e) {
                log.error("审核评论失败", e);
                return "审核评论失败: " + e.getMessage();
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
                Comment comment = client.get(Comment.class, id).block();
                if (comment == null) {
                    return "评论不存在: " + id;
                }
                client.delete(comment).block();
                return "评论已删除（ID: " + id + "）";
            } catch (Exception e) {
                log.error("删除评论失败", e);
                return "删除评论失败: " + e.getMessage();
            }
        }
    }
}
