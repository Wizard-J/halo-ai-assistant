package io.codex.haloaiassistant.agent.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ReactiveExtensionClient;

/** Versioned storage fixture deliberately separates returned objects from stored objects. */
class ArticleCreationConcurrencyTest {
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    final Map<String, Post> posts = new HashMap<>();
    final Map<String, Snapshot> snapshots = new HashMap<>();
    final ArticleTool.CreateArticleTool writer = new ArticleTool.CreateArticleTool(client);
    int updateConflicts, deleteConflicts, updateCalls, postDeleteCalls, snapshotDeleteCalls;
    boolean alwaysConflict, denyUpdate, denyPostDelete, denySnapshotDelete, uncertainWrite, foreignEdit;

    @BeforeEach void setup() {
        when(client.get(eq(User.class), anyString())).thenReturn(Mono.just(new User()));
        when(client.create(any(Post.class))).thenAnswer(call -> {
            Post created = copy(call.getArgument(0), Post.class);
            created.getMetadata().setVersion(0L);
            Post reconciled = copy(created, Post.class);
            reconciled.getMetadata().setVersion(1L);
            reconciled.getMetadata().setAnnotations(new HashMap<>(Map.of("controller", "preserve")));
            reconciled.getSpec().setTags(List.of("controller-tag"));
            if (foreignEdit) reconciled.getSpec().setHeadSnapshot("editor-snapshot");
            posts.put(created.getMetadata().getName(), reconciled);
            return Mono.just(created);
        });
        when(client.create(any(Snapshot.class))).thenAnswer(call -> {
            Snapshot created = copy(call.getArgument(0), Snapshot.class);
            created.getMetadata().setVersion(0L);
            Snapshot stored = copy(created, Snapshot.class);
            stored.getMetadata().setVersion(1L);
            snapshots.put(created.getMetadata().getName(), stored);
            return Mono.just(created);
        });
        when(client.fetch(eq(Post.class), anyString())).thenAnswer(call ->
                Mono.justOrEmpty(copy(posts.get(call.getArgument(1)), Post.class)));
        when(client.fetch(eq(Snapshot.class), anyString())).thenAnswer(call ->
                Mono.justOrEmpty(copy(snapshots.get(call.getArgument(1)), Snapshot.class)));
        when(client.update(any(Post.class))).thenAnswer(call -> {
            updateCalls++;
            Post proposed = call.getArgument(0);
            Post stored = posts.get(proposed.getMetadata().getName());
            if (denyUpdate) return Mono.error(new IllegalStateException("write denied"));
            if (alwaysConflict || updateConflicts-- > 0) {
                stored.getMetadata().setVersion(stored.getMetadata().getVersion() + 1);
                stored.getMetadata().getAnnotations().put("between-read-and-write", "preserve");
                return Mono.error(new OptimisticLockingFailureException("controller updated article"));
            }
            assertEquals(stored.getMetadata().getVersion(), proposed.getMetadata().getVersion());
            Post updated = copy(proposed, Post.class);
            updated.getMetadata().setVersion(updated.getMetadata().getVersion() + 1);
            posts.put(updated.getMetadata().getName(), updated);
            if (uncertainWrite) return Mono.error(new IllegalStateException("response lost after commit"));
            return Mono.just(copy(updated, Post.class));
        });
        when(client.delete(any(Post.class))).thenAnswer(call -> {
            postDeleteCalls++;
            Post proposed = call.getArgument(0);
            Post stored = posts.get(proposed.getMetadata().getName());
            if (denyPostDelete) return Mono.error(new IllegalStateException("delete denied"));
            if (deleteConflicts-- > 0) {
                stored.getMetadata().setVersion(stored.getMetadata().getVersion() + 1);
                return Mono.error(new OptimisticLockingFailureException("controller updated before delete"));
            }
            assertEquals(stored.getMetadata().getVersion(), proposed.getMetadata().getVersion());
            posts.remove(proposed.getMetadata().getName());
            return Mono.just(proposed);
        });
        when(client.delete(any(Snapshot.class))).thenAnswer(call -> {
            snapshotDeleteCalls++;
            Snapshot proposed = call.getArgument(0);
            if (denySnapshotDelete) return Mono.error(new IllegalStateException("snapshot delete denied"));
            assertTrue(posts.isEmpty(), "Do not delete body while the empty post still needs cleanup");
            assertEquals(snapshots.get(proposed.getMetadata().getName()).getMetadata().getVersion(), proposed.getMetadata().getVersion());
            snapshots.remove(proposed.getMetadata().getName());
            return Mono.just(proposed);
        });
    }

    @Test void usesLatestVersionAndPreservesControllerChanges() {
        assertTrue(create(true).startsWith("文章创建成功"));
        Post saved = posts.values().iterator().next();
        assertTrue(saved.getSpec().getPublish());
        assertEquals(saved.getSpec().getHeadSnapshot(), saved.getSpec().getReleaseSnapshot());
        assertEquals("preserve", saved.getMetadata().getAnnotations().get("controller"));
        assertEquals(List.of("controller-tag"), saved.getSpec().getTags());
        assertEquals(1, posts.size()); assertEquals(1, snapshots.size());
    }

    @Test void refetchesAfterConflictAndNeverCreatesDuplicateSnapshot() {
        updateConflicts = 2;
        assertTrue(create(true).startsWith("文章创建成功"));
        assertEquals(3, updateCalls);
        assertEquals("preserve", posts.values().iterator().next().getMetadata().getAnnotations().get("between-read-and-write"));
        assertEquals(1, posts.size()); assertEquals(1, snapshots.size());
        verify(client, times(1)).create(any(Post.class));
        verify(client, times(1)).create(any(Snapshot.class));
    }

    @Test void draftRemainsUnpublishedAfterConflict() {
        updateConflicts = 1;
        assertTrue(create(false).startsWith("文章创建成功"));
        Post saved = posts.values().iterator().next();
        assertFalse(saved.getSpec().getPublish()); assertNull(saved.getSpec().getReleaseSnapshot());
        assertNotNull(saved.getSpec().getHeadSnapshot());
    }

    @Test void exhaustedWriteRetriesCleanBothResourcesUsingFreshVersions() {
        alwaysConflict = true;
        deleteConflicts = 1;
        String result = create(true);
        assertTrue(result.startsWith("创建文章失败"));
        assertFalse(result.contains("回滚清理未完成"));
        assertEquals(5, updateCalls); assertEquals(2, postDeleteCalls);
        assertTrue(posts.isEmpty()); assertTrue(snapshots.isEmpty());
    }

    @Test void nonConflictFailureIsNotRetried() {
        denyUpdate = true;
        assertTrue(create(true).contains("write denied"));
        assertEquals(1, updateCalls);
        assertTrue(posts.isEmpty()); assertTrue(snapshots.isEmpty());
    }

    @Test void cleanupFailureIsVisibleAndPreservesBodyForRecovery() {
        denyUpdate = true; denyPostDelete = true;
        String result = create(true);
        assertTrue(result.contains("回滚清理未完成")); assertTrue(result.contains("Post=post-"));
        assertTrue(result.contains("Snapshot=")); assertTrue(result.contains("delete denied"));
        assertEquals(1, posts.size()); assertEquals(1, snapshots.size()); assertEquals(0, snapshotDeleteCalls);
    }

    @Test void snapshotCleanupFailureIsVisibleAfterPostCleanup() {
        denyUpdate = true; denySnapshotDelete = true;
        String result = create(true);
        assertTrue(result.contains("回滚清理未完成")); assertTrue(result.contains("snapshot delete denied"));
        assertTrue(posts.isEmpty()); assertEquals(1, snapshots.size());
    }

    @Test void lostSuccessResponseNeverDeletesAlreadyPublishedContent() {
        uncertainWrite = true;
        String result = create(true);
        assertTrue(result.contains("回滚保留"));
        assertEquals(1, posts.size()); assertEquals(1, snapshots.size());
        assertTrue(posts.values().iterator().next().getSpec().getPublish());
        assertEquals(0, postDeleteCalls); assertEquals(0, snapshotDeleteCalls);
    }

    @Test void foreignEditorContentIsNeverOverwrittenOrDeleted() {
        foreignEdit = true;
        String result = create(true);
        assertTrue(result.contains("已被其他操作修改"));
        assertEquals("editor-snapshot", posts.values().iterator().next().getSpec().getHeadSnapshot());
        assertEquals(0, updateCalls); assertEquals(0, postDeleteCalls); assertEquals(0, snapshotDeleteCalls);
    }

    private String create(boolean published) {
        return writer.executeInternal(mapper.createObjectNode().put("title", "并发文章测试")
                .put("content", "真实写入逻辑的合成测试正文").put("owner", "wizard-frontier")
                .put("status", published ? "published" : "draft"));
    }

    private <T> T copy(Object value, Class<T> type) {
        return value == null ? null : mapper.convertValue(value, type);
    }
}
