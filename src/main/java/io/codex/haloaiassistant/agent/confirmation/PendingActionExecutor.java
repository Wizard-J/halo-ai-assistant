package io.codex.haloaiassistant.agent.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.tools.ArticleTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.BatchTagArticlesTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.CreateArticleTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.DeleteArticleTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.UpdateArticleTool;
import io.codex.haloaiassistant.agent.tools.CategoryTool.CreateCategoryTool;
import io.codex.haloaiassistant.agent.tools.CategoryTool.DeleteCategoryTool;
import io.codex.haloaiassistant.agent.tools.CommentTool.ApproveCommentTool;
import io.codex.haloaiassistant.agent.tools.CommentTool.DeleteCommentTool;
import io.codex.haloaiassistant.agent.tools.TagTool.CreateTagTool;
import lombok.extern.slf4j.Slf4j;

/**
 * 待确认操作的内部执行器。
 * 直接调用工具的内部执行方法，不走公共 execute() 入口，
 * 避免确认操作被重新拦截为待确认。
 */
@Slf4j
public class PendingActionExecutor {

    /**
     * 执行已确认的操作。根据 type 分配对应的内部执行方法。
     * @return 执行结果文本
     */
    public static String execute(PendingAction action) {
        String type = action.getType();
        JsonNode payload = action.getPayload();

        try {
            return switch (type) {
                case "deleteArticle" -> executeDeleteArticle(payload);
                case "updateArticle" -> executeUpdateArticle(payload);
                case "createArticle" -> executeCreateArticle(payload);
                case "deleteComment" -> executeDeleteComment(payload);
                case "approveComment" -> executeApproveComment(payload);
                case "deleteCategory" -> executeDeleteCategory(payload);
                case "createCategory" -> executeCreateCategory(payload);
                case "createTag" -> executeCreateTag(payload);
                case "batchTagArticles" -> executeBatchTagArticles(payload);
                default -> throw new IllegalArgumentException("未知操作类型: " + type);
            };
        } catch (Exception e) {
            log.error("确认执行操作失败: type={}", type, e);
            return "操作执行失败: " + e.getMessage();
        }
    }

    private static String executeDeleteArticle(JsonNode args) {
        String id = args.get("id").asText();
        boolean permanent = args.has("permanent") && args.get("permanent").asBoolean();
        return ArticleTool.DeleteArticleTool.executeInternal(id, permanent);
    }

    private static String executeUpdateArticle(JsonNode args) {
        UpdateArticleTool tool = SpringContextBridge.getBean(UpdateArticleTool.class);
        return tool.executeInternal(args);
    }

    private static String executeCreateArticle(JsonNode args) {
        CreateArticleTool tool = SpringContextBridge.getBean(CreateArticleTool.class);
        return tool.executeInternal(args);
    }

    private static String executeBatchTagArticles(JsonNode args) {
        BatchTagArticlesTool tool = SpringContextBridge.getBean(BatchTagArticlesTool.class);
        return tool.executeInternal(args);
    }

    private static String executeDeleteComment(JsonNode args) {
        String id = args.get("id").asText();
        return CommentTool.DeleteCommentTool.executeInternal(id);
    }

    private static String executeApproveComment(JsonNode args) {
        String id = args.get("id").asText();
        return CommentTool.ApproveCommentTool.executeInternal(id);
    }

    private static String executeDeleteCategory(JsonNode args) {
        String id = args.get("id").asText();
        return CategoryTool.DeleteCategoryTool.executeInternal(id);
    }

    private static String executeCreateCategory(JsonNode args) {
        String name = args.get("name").asText();
        String slug = args.has("slug") ? args.get("slug").asText() : name.toLowerCase().replace(" ", "-");
        return CategoryTool.CreateCategoryTool.executeInternal(name, slug);
    }

    private static String executeCreateTag(JsonNode args) {
        String name = args.get("name").asText();
        String slug = name.toLowerCase().replace(" ", "-");
        return TagTool.CreateTagTool.executeInternal(name, slug);
    }
}
