package io.codex.haloaiassistant.agent.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import io.codex.haloaiassistant.agent.confirmation.RiskLevel;
import io.codex.haloaiassistant.agent.confirmation.SpringContextBridge;

/**
 * 待确认操作的分类/标签创建执行器。
 * 独立的静态方法，不使用 Tool.execute() 入口，避免确认操作被重新拦截。
 */
public final class ConfirmedExecutors {

    private ConfirmedExecutors() {}

    public static String createTag(String name, String slug) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        run.halo.app.core.extension.content.Tag tag = new run.halo.app.core.extension.content.Tag();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName("tag-" + java.time.Instant.now().toEpochMilli());
        metadata.setGenerateName("tag-");
        tag.setMetadata(metadata);
        var spec = new run.halo.app.core.extension.content.Tag.TagSpec();
        spec.setDisplayName(name);
        spec.setSlug(slug);
        tag.setSpec(spec);
        client.create(tag).block();
        return "标签创建成功：\n- 名称：" + name + "\n- 别名：" + slug;
    }

    public static String createCategory(String name, String slug) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        run.halo.app.core.extension.content.Category category = new run.halo.app.core.extension.content.Category();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName("category-" + java.time.Instant.now().toEpochMilli());
        metadata.setGenerateName("category-");
        category.setMetadata(metadata);
        var spec = new run.halo.app.core.extension.content.Category.CategorySpec();
        spec.setDisplayName(name);
        spec.setSlug(slug);
        category.setSpec(spec);
        client.create(category).block();
        return "分类创建成功：\n- 名称：" + name + "\n- 别名：" + slug;
    }

    public static String deleteCategory(String id) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        var cat = client.get(run.halo.app.core.extension.content.Category.class, id).block();
        if (cat == null) return "分类不存在: " + id;
        client.delete(cat).block();
        return "分类已删除（ID: " + id + "）";
    }

    public static String approveComment(String id) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        var comment = client.get(run.halo.app.core.extension.content.Comment.class, id).block();
        if (comment == null) return "评论不存在: " + id;
        comment.getSpec().setApproved(true);
        client.update(comment).block();
        return "评论已审核通过（ID: " + id + "）";
    }

    public static String deleteComment(String id) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        var comment = client.get(run.halo.app.core.extension.content.Comment.class, id).block();
        if (comment == null) return "评论不存在: " + id;
        client.delete(comment).block();
        return "评论已删除（ID: " + id + "）";
    }

    public static String deleteArticle(String id, boolean permanent) {
        var client = SpringContextBridge.getBean(run.halo.app.extension.ReactiveExtensionClient.class);
        var post = client.get(run.halo.app.core.extension.content.Post.class, id).block();
        if (post == null) return "文章不存在: " + id;
        if (permanent) {
            client.delete(post).block();
            return "文章已永久删除（ID: " + id + "）";
        }
        post.getSpec().setDeleted(true);
        client.update(post).block();
        return "文章已移入回收站（ID: " + id + "）";
    }
}
