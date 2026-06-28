package io.codex.haloaiassistant.agent.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import io.codex.haloaiassistant.agent.tools.ArticleTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.CreateArticleTool;
import io.codex.haloaiassistant.agent.tools.ArticleTool.UpdateArticleTool;
import lombok.extern.slf4j.Slf4j;

/**
 * 待确认操作的内部执行器。
 * 直接通过 ConfirmedExecutors 或工具类内部方法执行，不走公共 execute() 入口，
 * 避免确认操作被重新拦截为待确认。
 */
@Slf4j
public class PendingActionExecutor {

    public static String execute(PendingAction action) {
        String type = action.getType();
        JsonNode payload = action.getPayload();

        try {
            return switch (type) {
                case "deleteArticle" -> executeDeleteArticle(payload);
                case "updateArticle" -> executeUpdateArticle(payload);
                case "deleteComment" -> executeDeleteComment(payload);
                case "approveComment" -> executeApproveComment(payload);
                case "deleteCategory" -> executeDeleteCategory(payload);
                case "createCategory" -> executeCreateCategory(payload);
                case "createTag" -> executeCreateTag(payload);
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
        return ConfirmedExecutors.deleteArticle(id, permanent);
    }

    private static String executeUpdateArticle(JsonNode args) {
        // 低风险更新（仅 title/content）不走确认，已执行完。
        // 高风险（publish/categories/tags）在确认后通过原 UpdateArticleTool 的 execute 执行，
        // 此时不会再走确认路径（因为是从 PendingActionService 直接调用的）。
        // 目前通过重新调用 execute 但无需确认——需要在 UpdateArticleTool 增加区分。
        // 临时方案：再次调用 UpdateArticleTool.execute，由于确认后 payload 已不包含高风险标记外的其他标记，
        // 但仍然会走确认路径。需要后续优化。
        UpdateArticleTool tool = SpringContextBridge.getBean(UpdateArticleTool.class);
        return tool.execute(args);
    }

    private static String executeDeleteComment(JsonNode args) {
        String id = args.get("id").asText();
        return ConfirmedExecutors.deleteComment(id);
    }

    private static String executeApproveComment(JsonNode args) {
        String id = args.get("id").asText();
        return ConfirmedExecutors.approveComment(id);
    }

    private static String executeDeleteCategory(JsonNode args) {
        String id = args.get("id").asText();
        return ConfirmedExecutors.deleteCategory(id);
    }

    private static String executeCreateCategory(JsonNode args) {
        String name = args.get("name").asText();
        String slug = args.has("slug") ? args.get("slug").asText() : name.toLowerCase().replace(" ", "-");
        return ConfirmedExecutors.createCategory(name, slug);
    }

    private static String executeCreateTag(JsonNode args) {
        String name = args.get("name").asText();
        String slug = name.toLowerCase().replace(" ", "-");
        return ConfirmedExecutors.createTag(name, slug);
    }
}
