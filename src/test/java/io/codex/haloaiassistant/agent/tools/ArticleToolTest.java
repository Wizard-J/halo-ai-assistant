package io.codex.haloaiassistant.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Metadata;

class ArticleToolTest {

    @Test
    @DisplayName("normalizeStatus maps unpublished wording to draft")
    void normalizeStatusDraftAliases() {
        assertEquals("draft", ArticleTool.normalizeStatus("未发布"));
        assertEquals("draft", ArticleTool.normalizeStatus("草稿"));
        assertEquals("draft", ArticleTool.normalizeStatus("unpublished"));
        assertEquals("published", ArticleTool.normalizeStatus("已发布"));
        assertEquals("trash", ArticleTool.normalizeStatus("回收站"));
    }

    @Test
    @DisplayName("matchesStatus treats draft as unpublished and not deleted")
    void matchesDraftStatus() {
        Post draft = post(false, false);
        Post published = post(true, false);
        Post trashedDraft = post(false, true);

        assertTrue(ArticleTool.matchesStatus(draft, "draft"));
        assertFalse(ArticleTool.matchesStatus(published, "draft"));
        assertFalse(ArticleTool.matchesStatus(trashedDraft, "draft"));
        assertTrue(ArticleTool.matchesStatus(published, "published"));
        assertTrue(ArticleTool.matchesStatus(trashedDraft, "trash"));
    }

    private Post post(boolean published, boolean deleted) {
        Post post = new Post();
        var metadata = new Metadata();
        metadata.setName("post-" + published + "-" + deleted);
        post.setMetadata(metadata);
        var spec = new Post.PostSpec();
        spec.setPublish(published);
        spec.setDeleted(deleted);
        post.setSpec(spec);
        return post;
    }
}
