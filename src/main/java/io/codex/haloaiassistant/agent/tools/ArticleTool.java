package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
import io.codex.haloaiassistant.agent.confirmation.PendingActionService;
import io.codex.haloaiassistant.agent.confirmation.RiskLevel;
import io.codex.haloaiassistant.agent.confirmation.SpringContextBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PatchUtils;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import io.codex.haloaiassistant.config.AiAssistantSetting;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.extension.Ref;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleTool implements Tool {

    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "listArticles";
    }

    @Override
    public String getDescription() {
        return "获取文章列表，支持分页和状态筛选；当用户询问未发布、草稿、待发布文章时必须传 status=draft";
    }

    @Override
    public String getParametersJsonSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode pageProp = props.putObject("page");
        pageProp.put("type", "integer");
        pageProp.put("description", "页码，从 1 开始");
        pageProp.put("default", 1);

        ObjectNode sizeProp = props.putObject("size");
        sizeProp.put("type", "integer");
        sizeProp.put("description", "每页数量；按状态筛选时会一次返回全部匹配文章，不分页");
        sizeProp.put("default", 50);

        ObjectNode statusProp = props.putObject("status");
        statusProp.put("type", "string");
        statusProp.putArray("enum").add("published").add("draft").add("trash").add("");
        statusProp.put("description", "文章状态筛选：published=已发布，draft=未发布/草稿，trash=回收站；用户问未发布时使用 draft");
        statusProp.put("default", "");

        return schema.toPrettyString();
    }

    @Override
    public String execute(JsonNode args) {
        int page = args.has("page") ? args.get("page").asInt(1) : 1;
        int size = args.has("size") ? args.get("size").asInt(50) : 50;
        String status = normalizeStatus(args.has("status") ? args.get("status").asText("") : "");

        try {
            int fetchPage = status.isBlank() ? page - 1 : 0;
            int fetchSize = status.isBlank() ? size : 500;
            ListResult<Post> result = client.list(Post.class, null, null, fetchPage, fetchSize).block();
            if (result == null) {
                return "获取文章列表失败：无返回结果";
            }

            List<Post> filtered = result.getItems().stream()
                    .filter(post -> matchesStatus(post, status))
                    .toList();
            long total = status.isBlank() ? result.getTotal() : filtered.size();
            int totalPages = status.isBlank() ? Math.max(1, (int) Math.ceil(total / (double) size)) : 1;
            int from = status.isBlank() ? Math.min(Math.max(page - 1, 0) * size, filtered.size()) : 0;
            int to = status.isBlank() ? Math.min(from + size, filtered.size()) : filtered.size();
            List<Post> pageItems = status.isBlank() ? result.getItems() : filtered.subList(from, to);

            StringBuilder sb = new StringBuilder();
            if (status.isBlank()) {
                sb.append(String.format("共 %d 篇文章（当前第 %d/%d 页）\n\n",
                        total, page, totalPages));
            } else {
                sb.append(String.format("当前共有 %d 篇%s文章，已全部列出：\n\n",
                        total, statusLabel(status)));
            }
            int rowNumber = from + 1;
            for (Post post : pageItems) {
                var meta = post.getMetadata();
                var spec = post.getSpec();
                String title = spec != null && spec.getTitle() != null ? spec.getTitle() : "无标题";
                String postStatus = isPublished(post)
                        ? "已发布" : isDeleted(post)
                        ? "回收站" : "草稿";
                String publishTime = spec != null && spec.getPublishTime() != null
                        ? spec.getPublishTime().toString() : "未设置";
                sb.append(String.format("%d. %s（%s，时间：%s）\n",
                        rowNumber++, title, postStatus, publishTime));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取文章列表失败", e);
            return "获取文章列表失败: " + e.getMessage();
        }
    }

    static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String normalized = status.trim().toLowerCase();
        return switch (normalized) {
            case "published", "publish", "已发布" -> "published";
            case "draft", "unpublished", "未发布", "草稿", "待发布" -> "draft";
            case "trash", "deleted", "回收站", "已删除" -> "trash";
            default -> "";
        };
    }

    static boolean matchesStatus(Post post, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return switch (status) {
            case "published" -> isPublished(post) && !isDeleted(post);
            case "draft" -> !isPublished(post) && !isDeleted(post);
            case "trash" -> isDeleted(post);
            default -> true;
        };
    }

    static boolean isPublished(Post post) {
        var spec = post.getSpec();
        if (spec != null && spec.getPublish() != null) {
            return spec.getPublish();
        }
        return post.isPublished();
    }

    static boolean isDeleted(Post post) {
        var spec = post.getSpec();
        if (spec != null && spec.getDeleted() != null) {
            return spec.getDeleted();
        }
        return post.isDeleted();
    }

    static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "published" -> "已发布";
            case "draft" -> "草稿/未发布";
            case "trash" -> "回收站";
            default -> "";
        };
    }

    public static List<String> splitNames(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,，、]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static class CreateArticleTool implements Tool {
        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public CreateArticleTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "createArticle";
        }

        @Override
        public String getDescription() {
            return "创建新文章，需要提供标题和内容，可选分类和标签";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode titleProp = props.putObject("title");
            titleProp.put("type", "string");
            titleProp.put("description", "文章标题");

            ObjectNode contentProp = props.putObject("content");
            contentProp.put("type", "string");
            contentProp.put("description", "文章内容（Markdown 格式）");

            ObjectNode statusProp = props.putObject("status");
            statusProp.put("type", "string");
            statusProp.putArray("enum").add("draft").add("published");
            statusProp.put("description", "发布状态");
            statusProp.put("default", "draft");

            ObjectNode categoriesProp = props.putObject("categories");
            categoriesProp.put("type", "string");
            categoriesProp.put("description", "分类名称（多个用逗号分隔）");
            categoriesProp.put("default", "");

            ObjectNode tagsProp = props.putObject("tags");
            tagsProp.put("type", "string");
            tagsProp.put("description", "标签名称（多个用逗号分隔）");
            tagsProp.put("default", "");

            ObjectNode publishTimeProp = props.putObject("publishTime");
            publishTimeProp.put("type", "string");
            publishTimeProp.put("description",
                    "发布时间，可使用 ISO 8601 或 yyyy-MM-dd HH:mm:ss；不含时区时按 Asia/Shanghai 解析");

            ObjectNode ownerProp = props.putObject("owner");
            ownerProp.put("type", "string");
            ownerProp.put("description", "可选的 Halo 作者用户名；不填写时使用博客现有作者");

            ArrayNode required = schema.putArray("required");
            required.add("title");
            required.add("content");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String title = args.path("title").asText("").trim();
            String content = args.path("content").asText("");
            boolean published = "published".equals(args.has("status") ? args.get("status").asText("draft") : "draft");
            String categories = args.has("categories") ? args.get("categories").asText("") : "";
            String tags = args.has("tags") ? args.get("tags").asText("") : "";
            Instant publishTime = args.hasNonNull("publishTime")
                    ? parsePublishTime(args.get("publishTime").asText()) : null;

            // 仅当发布操作时才走确认
            if (published) {
                try {
                    PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                    String summary = "将创建文章「" + (title.length() > 40 ? title.substring(0, 40) + "…" : title) + "」并立即发布。";
                    var result = pas.create("createArticle", "发布文章确认", summary, RiskLevel.MEDIUM, args);
                    return "⚠️ 需要确认操作\n\n"
                            + "**待确认操作**\n\n"
                            + "**操作：** 发布文章确认\n"
                            + "**摘要：** " + summary + "\n"
                            + "**风险等级：** MEDIUM\n\n"
                            + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                            + "请管理员点击确认后执行操作。";
                } catch (Exception e) {
                    log.error("创建待确认操作失败，已取消执行", e);
                    return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
                }
            }

            return executeInternal(args);
        }

        /**
         * 内部执行方法——确认后直接创建文章，不走确认路径。
         */
        public String executeInternal(JsonNode args) {
            String title = args.path("title").asText("").trim();
            String content = args.path("content").asText("");
            boolean published = "published".equals(args.has("status") ? args.get("status").asText("draft") : "draft");
            String categories = args.has("categories") ? args.get("categories").asText("") : "";
            String tags = args.has("tags") ? args.get("tags").asText("") : "";
            Instant publishTime = args.hasNonNull("publishTime")
                    ? parsePublishTime(args.get("publishTime").asText()) : null;

            if (title.isBlank()) {
                return "创建文章失败：标题不能为空";
            }
            if (content.isBlank()) {
                return "创建文章失败：内容不能为空";
            }

            Post createdPost = null;
            Snapshot createdSnapshot = null;
            try {
                String owner = args.hasNonNull("owner") && !args.get("owner").asText().isBlank()
                        ? requireOwner(args.get("owner").asText()) : resolveOwner();
                String postName = "post-" + UUID.randomUUID();

                Post post = new Post();
                var metadata = new Metadata();
                metadata.setName(postName);
                post.setMetadata(metadata);

                var spec = new Post.PostSpec();
                spec.setTitle(title);
                spec.setSlug(postName);
                spec.setOwner(owner);
                spec.setDeleted(false);
                spec.setPublish(false);
                spec.setPublishTime(publishTime);
                spec.setPinned(false);
                spec.setAllowComment(true);
                spec.setVisible(Post.VisibleEnum.PUBLIC);
                spec.setPriority(0);
                var excerpt = new Post.Excerpt();
                excerpt.setAutoGenerate(true);
                spec.setExcerpt(excerpt);
                spec.setCategories(splitNames(categories));
                spec.setTags(splitNames(tags));
                post.setSpec(spec);

                createdPost = client.create(post).block();
                if (createdPost == null) {
                    return "创建文章失败：Halo 未返回新文章";
                }

                createdSnapshot = createBaseSnapshot(createdPost, owner, content);
                String snapshotName = createdSnapshot.getMetadata().getName();
                createdPost.getSpec().setBaseSnapshot(snapshotName);
                createdPost.getSpec().setHeadSnapshot(snapshotName);
                if (published) {
                    createdPost.getSpec().setPublish(true);
                    createdPost.getSpec().setReleaseSnapshot(snapshotName);
                }

                Post completed = client.update(createdPost).block();
                if (completed == null) {
                    throw new IllegalStateException("Halo 未能关联文章内容快照");
                }

                String id = completed.getMetadata().getName();
                String status = published ? "已发布" : "草稿";
                return String.format("文章创建成功！\n- 标题：%s\n- 状态：%s\n- 发布时间：%s\n- ID：%s",
                        title, status, completed.getSpec().getPublishTime(), id);
            } catch (Exception e) {
                log.error("创建文章失败", e);
                rollback(createdSnapshot, createdPost);
                return "创建文章失败: " + rootMessage(e);
            }
        }

        private Snapshot createBaseSnapshot(Post post, String owner, String markdown) {
            String html = toReadableHtml(markdown);
            Snapshot snapshot = new Snapshot();
            Metadata metadata = new Metadata();
            metadata.setName(UUID.randomUUID().toString());
            metadata.setAnnotations(new HashMap<>());
            metadata.getAnnotations().put(Snapshot.KEEP_RAW_ANNO, Boolean.TRUE.toString());
            snapshot.setMetadata(metadata);

            var spec = new Snapshot.SnapShotSpec();
            spec.setSubjectRef(Ref.of(post));
            // Halo's built-in editor only provides the HTML format. Store both raw and rendered
            // content as HTML so generated drafts open directly without requiring a Markdown
            // editor plugin or showing the content format converter.
            spec.setRawType("HTML");
            spec.setRawPatch(html);
            spec.setContentPatch(html);
            spec.setLastModifyTime(Instant.now());
            spec.setOwner(owner);
            spec.setContributors(new LinkedHashSet<>(List.of(owner)));
            snapshot.setSpec(spec);

            Snapshot created = client.create(snapshot).block();
            if (created == null) {
                throw new IllegalStateException("Halo 未返回文章内容快照");
            }
            return created;
        }

        private String resolveOwner() {
            ListResult<Post> posts = client.list(Post.class, null, null, 0, 1).block();
            if (posts != null && !posts.getItems().isEmpty()) {
                String owner = posts.getItems().getFirst().getSpec().getOwner();
                if (owner != null && !owner.isBlank()) {
                    return owner;
                }
            }

            ListResult<User> users = client.list(User.class, null, null, 0, 1).block();
            if (users != null && !users.getItems().isEmpty()) {
                return users.getItems().getFirst().getMetadata().getName();
            }
            throw new IllegalStateException("找不到可作为文章作者的 Halo 用户");
        }

        private String requireOwner(String username) {
            User user = client.get(User.class, username).block();
            if (user == null) {
                throw new IllegalStateException("指定作者不存在: " + username);
            }
            return username;
        }

        private List<String> splitNames(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split("[,，]"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }

        private static String toReadableHtml(String markdown) {
            String escaped = markdown
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            StringBuilder html = new StringBuilder();
            boolean inList = false;
            for (String line : escaped.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    if (!inList) {
                        html.append("<ul>");
                        inList = true;
                    }
                    html.append("<li>").append(renderInline(trimmed.substring(2))).append("</li>");
                    continue;
                }
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                if (trimmed.isBlank()) {
                    continue;
                }
                int heading = 0;
                while (heading < trimmed.length() && heading < 6 && trimmed.charAt(heading) == '#') {
                    heading++;
                }
                if (heading > 0 && heading < trimmed.length() && trimmed.charAt(heading) == ' ') {
                    html.append("<h").append(heading).append(">").append(renderInline(trimmed.substring(heading + 1)))
                            .append("</h").append(heading).append(">");
                } else {
                    html.append("<p>").append(renderInline(trimmed)).append("</p>");
                }
            }
            if (inList) {
                html.append("</ul>");
            }
            return html.toString();
        }

        private static String renderInline(String value) {
            return value
                    .replaceAll("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)",
                            "<a href=\"$2\" target=\"_blank\" rel=\"noopener noreferrer\">$1</a>")
                    .replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
                    .replaceAll("`([^`]+)`", "<code>$1</code>");
        }

        private void rollback(Snapshot snapshot, Post post) {
            if (snapshot != null) {
                client.delete(snapshot).onErrorResume(error -> Mono.empty()).block();
            }
            if (post != null) {
                client.delete(post).onErrorResume(error -> Mono.empty()).block();
            }
        }

        private static String rootMessage(Throwable error) {
            Throwable current = error;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            String message = current.getMessage();
            return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        }
    }

    @Component
    public static class GetArticleTool implements Tool {

        private final ReactiveExtensionClient client;
        private final PostContentService postContentService;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public GetArticleTool(ReactiveExtensionClient client, PostContentService postContentService) {
            this.client = client;
            this.postContentService = postContentService;
        }

        @Override
        public String getName() {
            return "getArticle";
        }

        @Override
        public String getDescription() {
            return "获取指定文章的标题、发布时间和正文，可用于读取正文中的 date 信息";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "文章 ID");
            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.path("id").asText("");
            try {
                            Post post = client.get(Post.class, id).block();
                if (post == null) {
                    return "文章不存在: " + id;
                }
                ContentWrapper content = postContentService.getHeadContent(id).block();
                String raw = content == null ? "" : content.getRaw();
                if (raw.length() > 20000) {
                    raw = raw.substring(0, 20000) + "\n[正文过长，已截断]";
                }
                return String.format("文章信息：\n- ID：%s\n- 标题：%s\n- 发布时间：%s\n\n正文：\n%s",
                        id, post.getSpec().getTitle(), post.getSpec().getPublishTime(), raw);
            } catch (Exception e) {
                log.error("获取文章详情失败", e);
                return "获取文章详情失败: " + CreateArticleTool.rootMessage(e);
            }
        }
    }

    @Component
    public static class SyncArticlePublishTimesTool implements Tool {

        private static final List<Pattern> DATE_PATTERNS = List.of(
                Pattern.compile("(?im)^[\\s>*#-]*(?:date|publishTime|publish_time|publishedAt|published_at)\\s*[:：]\\s*[\\\"']?([^\\\"'\\r\\n<]+)"),
                Pattern.compile("(?i)[\\\"'](?:date|publishTime|publish_time|publishedAt|published_at)[\\\"']\\s*[:：]\\s*[\\\"']([^\\\"']+)"),
                Pattern.compile("(?i)(?:date|publishTime|publish_time|publishedAt|published_at)\\s*[:：]\\s*([^<\\r\\n]+)")
        );

        private final ReactiveExtensionClient client;
        private final PostContentService postContentService;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public SyncArticlePublishTimesTool(ReactiveExtensionClient client,
                                           PostContentService postContentService) {
            this.client = client;
            this.postContentService = postContentService;
        }

        @Override
        public String getName() {
            return "syncArticlePublishTimes";
        }

        @Override
        public String getDescription() {
            return "一次调用批量扫描文章正文中的 date/publishTime 等日期字段，并预览或同步到实际发布时间；"
                    + "适合大量文章，默认只预览不修改";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode dryRunProp = props.putObject("dryRun");
            dryRunProp.put("type", "boolean");
            dryRunProp.put("description", "true 仅预览，false 确认执行修改；建议先预览");
            dryRunProp.put("default", true);

            ObjectNode limitProp = props.putObject("limit");
            limitProp.put("type", "integer");
            limitProp.put("minimum", 1);
            limitProp.put("maximum", 500);
            limitProp.put("description", "最多扫描文章数");
            limitProp.put("default", 100);

            ObjectNode zoneProp = props.putObject("timezone");
            zoneProp.put("type", "string");
            zoneProp.put("description", "无时区日期使用的 IANA 时区，例如 Asia/Shanghai");
            zoneProp.put("default", "Asia/Shanghai");

            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            boolean dryRun = !args.has("dryRun") || args.get("dryRun").asBoolean(true);

            // 非预览模式需要确认
            if (!dryRun) {
                try {
                    PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                    int limit = Math.max(1, Math.min(500, args.path("limit").asInt(100)));
                    String timezone = args.path("timezone").asText("Asia/Shanghai");
                    String summary = "将批量同步最多 " + limit + " 篇文章的发布时间，时区：" + timezone;
                    var result = pas.create("syncArticlePublishTimes", "批量同步发布时间确认", summary, RiskLevel.HIGH, args);
                    return "⚠️ 需要确认操作\n\n"
                            + "**待确认操作**\n\n"
                            + "**操作：** 批量同步发布时间确认\n"
                            + "**摘要：** " + summary + "\n"
                            + "**风险等级：** HIGH\n\n"
                            + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                            + "请管理员点击确认后执行操作。";
                } catch (Exception e) {
                    log.error("创建待确认操作失败，已取消执行", e);
                    return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
                }
            }

            return executeInternal(args);
        }

        public String executeInternal(JsonNode args) {
            boolean dryRun = !args.has("dryRun") || args.get("dryRun").asBoolean(true);
            int limit = Math.max(1, Math.min(500, args.path("limit").asInt(100)));
            String timezone = args.path("timezone").asText("Asia/Shanghai");
            ZoneId zone;
            try {
                zone = ZoneId.of(timezone);
            } catch (Exception e) {
                return "批量同步失败：无效时区 " + timezone;
            }

            int scanned = 0;
            int matched = 0;
            int changed = 0;
            int skipped = 0;
            int failed = 0;
            List<String> details = new ArrayList<>();

            try {
                int page = 0;
                int pageSize = Math.min(100, limit);
                while (scanned < limit) {
                    ListResult<Post> result = client.list(
                            Post.class, null, null, page, pageSize).block();
                    if (result == null || result.getItems().isEmpty()) {
                        break;
                    }

                    for (Post post : result.getItems()) {
                        if (scanned >= limit) {
                            break;
                        }
                        scanned++;
                        String id = post.getMetadata().getName();
                        String title = post.getSpec().getTitle();
                        if (post.isDeleted()) {
                            skipped++;
                            continue;
                        }

                        try {
                            ContentWrapper content = postContentService.getHeadContent(id).block();
                            String raw = content == null ? "" : content.getRaw();
                            String dateValue = extractDateValue(raw);
                            if (dateValue == null) {
                                skipped++;
                                continue;
                            }
                            matched++;
                            Instant target = parsePublishTime(dateValue, zone);
                            Instant old = post.getSpec().getPublishTime();
                            if (target.equals(old)) {
                                skipped++;
                                details.add(String.format("- 跳过（时间一致）｜%s｜%s", title, target));
                                continue;
                            }

                            if (!dryRun) {
                                post.getSpec().setPublishTime(target);
                                Post updated = client.update(post).block();
                                if (updated == null) {
                                    throw new IllegalStateException("Halo 未返回更新后的文章");
                                }
                            }
                            changed++;
                            details.add(String.format("- %s｜%s｜%s → %s",
                                    dryRun ? "待修改" : "已修改", title,
                                    old == null ? "未设置" : old, target));
                        } catch (Exception articleError) {
                            failed++;
                            details.add(String.format("- 失败｜%s｜%s", title,
                                    CreateArticleTool.rootMessage(articleError)));
                            log.warn("同步文章发布时间失败, id={}", id, articleError);
                        }
                    }

                    page++;
                    if (page >= result.getTotalPages()) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("批量同步文章发布时间失败", e);
                return "批量同步失败: " + CreateArticleTool.rootMessage(e);
            }

            StringBuilder summary = new StringBuilder();
            summary.append(dryRun ? "批量同步预览完成" : "批量同步执行完成")
                    .append("！\n- 扫描：").append(scanned)
                    .append("\n- 匹配日期：").append(matched)
                    .append("\n- ").append(dryRun ? "待修改" : "已修改").append("：").append(changed)
                    .append("\n- 跳过：").append(skipped)
                    .append("\n- 失败：").append(failed);
            if (!details.isEmpty()) {
                summary.append("\n\n明细：\n");
                int detailLimit = Math.min(details.size(), 100);
                for (int i = 0; i < detailLimit; i++) {
                    summary.append(details.get(i)).append('\n');
                }
                if (details.size() > detailLimit) {
                    summary.append("- 其余 ").append(details.size() - detailLimit).append(" 条已省略\n");
                }
            }
            if (dryRun && changed > 0) {
                summary.append("\n确认无误后，请调用本工具并设置 dryRun=false 执行修改。");
            }
            return summary.toString().trim();
        }

        private static String extractDateValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw
                    .replace("&nbsp;", " ")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
            for (Pattern pattern : DATE_PATTERNS) {
                Matcher matcher = pattern.matcher(normalized);
                if (matcher.find()) {
                    String candidate = matcher.group(1).trim();
                    candidate = candidate.replaceFirst("\\s*(?:-->|</[^>]+>|[,;，；])\\s*$", "").trim();
                    if (!candidate.isBlank()) {
                        return candidate;
                    }
                }
            }
            return null;
        }
    }

    @Component
    public static class UpdateArticleTool implements Tool {

        private final ReactiveExtensionClient client;
        private final PostContentService postContentService;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public UpdateArticleTool(ReactiveExtensionClient client, PostContentService postContentService) {
            this.client = client;
            this.postContentService = postContentService;
        }

        @Override
        public String getName() {
            return "updateArticle";
        }

        @Override
        public String getDescription() {
            return "更新文章标题、内容、标签、分类、发布状态或发布时间";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "文章 ID");

            ObjectNode titleProp = props.putObject("title");
            titleProp.put("type", "string");
            titleProp.put("description", "新的文章标题");

            ObjectNode contentProp = props.putObject("content");
            contentProp.put("type", "string");
            contentProp.put("description", "新的文章内容（Markdown 格式）");

            ObjectNode publishProp = props.putObject("publish");
            publishProp.put("type", "boolean");
            publishProp.put("description", "是否发布");

            ObjectNode publishTimeProp = props.putObject("publishTime");
            publishTimeProp.put("type", "string");
            publishTimeProp.put("description",
                    "新的发布时间，可使用 ISO 8601 或 yyyy-MM-dd HH:mm:ss；不含时区时按 Asia/Shanghai 解析");

            ObjectNode categoriesProp = props.putObject("categories");
            categoriesProp.put("type", "string");
            categoriesProp.put("description", "分类名称（多个用逗号分隔，传空字符串清空）");

            ObjectNode tagsProp = props.putObject("tags");
            tagsProp.put("type", "string");
            tagsProp.put("description", "标签名称（多个用逗号分隔，传空字符串清空）");

            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.get("id").asText();

            // 高风险操作（发布/批量改标签分类）需要确认
            boolean hasHighRisk = args.has("publish") || args.has("categories") || args.has("tags");
            if (hasHighRisk) {
                try {
                    PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                    String summary = "将更新文章（ID: " + id + "）";
                    if (args.has("publish") && args.get("publish").asBoolean()) {
                        summary += "，发布文章";
                    }
                    if (args.has("categories")) {
                        summary += "，更新分类";
                    }
                    if (args.has("tags")) {
                        summary += "，更新标签";
                    }
                    var result = pas.create("updateArticle", "更新文章确认", summary, RiskLevel.HIGH, args);
                    return "⚠️ 需要确认操作\n\n"
                            + "**待确认操作**\n\n"
                            + "**操作：** 更新文章确认\n"
                            + "**摘要：** " + summary + "\n"
                            + "**风险等级：** HIGH\n\n"
                            + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                            + "请管理员点击确认后执行操作。";
                } catch (Exception e) {
                    log.error("创建待确认操作失败，已取消执行", e);
                    return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
                }
            }

            return executeInternal(args);
        }

        public String executeInternal(JsonNode args) {
            String id = args.get("id").asText();
            Snapshot createdSnapshot = null;

            try {
                Post post = client.get(Post.class, id).block();
                if (post == null) {
                    return "文章不存在: " + id;
                }

                if (args.has("title")) {
                    post.getSpec().setTitle(args.get("title").asText());
                }
                if (args.has("content")) {
                    String newContent = args.get("content").asText();
                    if (newContent.isBlank()) {
                        return "文章更新失败：内容不能为空";
                    }
                    createdSnapshot = createUpdateSnapshot(post, newContent);
                    post.getSpec().setHeadSnapshot(createdSnapshot.getMetadata().getName());
                }
                if (args.has("categories")) {
                    var categories = args.get("categories").asText();
                    post.getSpec().setCategories(
                            categories.isBlank() ? null : splitNames(categories));
                }
                if (args.has("tags")) {
                    var tags = args.get("tags").asText();
                    post.getSpec().setTags(
                            tags.isBlank() ? null : splitNames(tags));
                }
                if (args.hasNonNull("publishTime")) {
                    post.getSpec().setPublishTime(parsePublishTime(args.get("publishTime").asText()));
                }
                if (args.has("publish")) {
                    boolean publish = args.get("publish").asBoolean();
                    post.getSpec().setPublish(publish);
                    if (publish) {
                        String headSnapshot = post.getSpec().getHeadSnapshot();
                        if (headSnapshot == null || headSnapshot.isBlank()) {
                            throw new IllegalStateException("文章没有可发布的内容快照");
                        }
                        post.getSpec().setReleaseSnapshot(headSnapshot);
                    }
                }

                Post updated = client.update(post).block();
                if (updated == null) {
                    throw new IllegalStateException("Halo 未返回更新后的文章");
                }
                return "文章更新成功！"
                        + (args.has("title") ? "\n新标题：" + args.get("title").asText() : "")
                        + (args.has("publishTime") ? "\n新发布时间：" + updated.getSpec().getPublishTime() : "");
            } catch (Exception e) {
                log.error("更新文章失败", e);
                if (createdSnapshot != null) {
                    client.delete(createdSnapshot).onErrorResume(error -> Mono.empty()).block();
                }
                return "更新文章失败: " + CreateArticleTool.rootMessage(e);
            }
        }

        private Snapshot createUpdateSnapshot(Post post, String markdown) {
            String postName = post.getMetadata().getName();
            String baseSnapshotName = post.getSpec().getBaseSnapshot();
            if (baseSnapshotName == null || baseSnapshotName.isBlank()) {
                throw new IllegalStateException("文章缺少基础内容快照，无法安全更新");
            }

            ContentWrapper base = postContentService
                    .getSpecifiedContent(postName, baseSnapshotName)
                    .block();
            if (base == null) {
                throw new IllegalStateException("无法读取文章基础内容快照");
            }

            String owner = post.getSpec().getOwner();
            if (owner == null || owner.isBlank()) {
                throw new IllegalStateException("文章缺少作者信息");
            }

            Snapshot snapshot = new Snapshot();
            Metadata metadata = new Metadata();
            metadata.setName(UUID.randomUUID().toString());
            snapshot.setMetadata(metadata);

            var spec = new Snapshot.SnapShotSpec();
            spec.setSubjectRef(Ref.of(post));
            String html = CreateArticleTool.toReadableHtml(markdown);
            spec.setRawType("HTML");
            spec.setRawPatch(PatchUtils.diffToJsonPatch(base.getRaw(), html));
            spec.setContentPatch(PatchUtils.diffToJsonPatch(base.getContent(), html));
            spec.setParentSnapshotName(post.getSpec().getHeadSnapshot());
            spec.setLastModifyTime(Instant.now());
            spec.setOwner(owner);
            spec.setContributors(new LinkedHashSet<>(List.of(owner)));
            snapshot.setSpec(spec);

            Snapshot created = client.create(snapshot).block();
            if (created == null) {
                throw new IllegalStateException("Halo 未返回新的文章内容快照");
            }
            return created;
        }
    }

    @Component
    public static class DeleteArticleTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public DeleteArticleTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "deleteArticle";
        }

        @Override
        public String getDescription() {
            return "删除文章（移入回收站或永久删除）";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode idProp = props.putObject("id");
            idProp.put("type", "string");
            idProp.put("description", "文章 ID");

            ObjectNode permanentProp = props.putObject("permanent");
            permanentProp.put("type", "boolean");
            permanentProp.put("description", "是否永久删除");
            permanentProp.put("default", false);

            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            String id = args.get("id").asText();
            boolean permanent = args.has("permanent") && args.get("permanent").asBoolean();

            // 删除必须确认
            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String type = "deleteArticle";
                String title = permanent ? "永久删除文章确认" : "删除文章确认";
                String summary = String.format("将%s文章（ID: %s）。此操作不可撤销。",
                        permanent ? "永久删除" : "移入回收站", id);
                var result = pas.create(type, title, summary, RiskLevel.HIGH, args);

                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** " + title + "\n"
                        + "**摘要：** " + summary + "\n"
                        + "**风险等级：** HIGH\n\n"
                        + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                        + "请管理员点击确认后执行操作。";
            } catch (Exception e) {
                log.error("创建待确认操作失败，已取消执行", e);
                return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
            }
        }

        /**
         * 内部执行方法——确认后直接执行删除，不走确认路径。
         */
        public static String executeInternal(String id, boolean permanent) {
            ReactiveExtensionClient client = SpringContextBridge.getBean(ReactiveExtensionClient.class);
            try {
                if (permanent) {
                    Post post = client.get(Post.class, id).block();
                    if (post == null) {
                        return "文章不存在: " + id;
                    }
                    client.delete(post).block();
                    return "文章已永久删除（ID: " + id + "）";
                } else {
                    Post post = client.get(Post.class, id).block();
                    if (post == null) {
                        return "文章不存在: " + id;
                    }
                    var spec = post.getSpec();
                    spec.setDeleted(true);
                    client.update(post).block();
                    return "文章已移入回收站（ID: " + id + "）";
                }
            } catch (Exception e) {
                log.error("删除文章失败", e);
                return "删除文章失败: " + e.getMessage();
            }
        }
    }

    private static Instant parsePublishTime(String value) {
        return parsePublishTime(value, ZoneId.of("Asia/Shanghai"));
    }

    private static Instant parsePublishTime(String value, ZoneId zone) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("发布时间不能为空");
        }
        // ISO parsers accept fractional seconds from 1 to 9 digits, but require `T` between
        // date and time. Normalize common database-style values such as
        // `2019-09-07 07:26:26.052628` before parsing.
        String isoText = text
                .replaceFirst("^(\\d{4}-\\d{2}-\\d{2})\\s+(?=\\d{2}:)", "$1T")
                .replaceFirst("(?<=\\d),(?=\\d{1,9}(?:Z|[+-]\\d{2}:?\\d{2})?$)", ".");
        try {
            return Instant.parse(isoText);
        } catch (DateTimeParseException ignored) {
            // Continue with an offset or local date-time.
        }
        try {
            return OffsetDateTime.parse(isoText, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with local formats.
        }
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with local formats.
        }
        if (text.matches("\\d{10}|\\d{13}")) {
            long timestamp = Long.parseLong(text);
            return text.length() == 10 ? Instant.ofEpochSecond(timestamp) : Instant.ofEpochMilli(timestamp);
        }
        try {
            return LocalDateTime.parse(isoText, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with non-ISO local formats.
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
                DateTimeFormatter.ofPattern("yyyy.M.d H:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy.M.d H:mm"),
                DateTimeFormatter.ofPattern("yyyy年M月d日 H时m分s秒"),
                DateTimeFormatter.ofPattern("yyyy年M月d日 H时m分"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy.M.d"),
                DateTimeFormatter.ofPattern("yyyy年M月d日"))) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                // Try the next supported date-only format.
            }
        }
        throw new IllegalArgumentException("无法识别日期：" + text);
    }
    /**
     * 批量更新文章标签和分类 — 一次调用处理多篇文章，避免 AI 逐篇更新导致的 depth 限制
     */
    public static class BatchTagArticlesTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public BatchTagArticlesTool(ReactiveExtensionClient client) {
            this.client = client;
        }

        @Override
        public String getName() {
            return "batchTagArticles";
        }

        @Override
        public String getDescription() {
            return "批量更新多篇文章的标签和分类，可指定文章 ID 列表或更新全部文章";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode tagsProp = props.putObject("tags");
            tagsProp.put("type", "string");
            tagsProp.put("description", "要设置的标签名称，多个用逗号分隔");
            tagsProp.put("default", "");

            ObjectNode categoriesProp = props.putObject("categories");
            categoriesProp.put("type", "string");
            categoriesProp.put("description", "要设置的分类名称，多个用逗号分隔");
            categoriesProp.put("default", "");

            ObjectNode articleIdsProp = props.putObject("articleIds");
            articleIdsProp.put("type", "string");
            articleIdsProp.put("description", "要更新的文章 ID 列表，逗号分隔；不传则更新所有文章");
            articleIdsProp.put("default", "");

            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            // 批量更新标签/分类需要确认
            try {
                PendingActionService pas = SpringContextBridge.getBean(PendingActionService.class);
                String ids = args.has("articleIds") ? args.get("articleIds").asText("") : "";
                String summary = "将批量更新文章标签/分类。影响文章：" + (ids.isBlank() ? "全部" : ids);
                var result = pas.create("batchTagArticles", "批量更新确认", summary, RiskLevel.HIGH, args);
                return "⚠️ 需要确认操作\n\n"
                        + "**待确认操作**\n\n"
                        + "**操作：** 批量更新确认\n"
                        + "**摘要：** " + summary + "\n"
                        + "**风险等级：** HIGH\n\n"
                        + "**确认ID：** `" + result.getConfirmationId() + "`\n\n"
                        + "请管理员点击确认后执行操作。";
            } catch (Exception e) {
                log.error("创建待确认操作失败，已取消执行", e);
                return "[错误] 无法创建待确认操作，已取消执行。请稍后重试。";
            }
        }

        public String executeInternal(JsonNode args) {
            try {
                List<String> tags = args.has("tags")
                        ? ArticleTool.splitNames(args.get("tags").asText())
                        : List.of();
                List<String> categories = args.has("categories")
                        ? ArticleTool.splitNames(args.get("categories").asText())
                        : List.of();

                if (tags.isEmpty() && categories.isEmpty()) {
                    return "请至少指定 tags 或 categories 参数";
                }

                List<Post> posts;
                if (args.has("articleIds") && !args.get("articleIds").asText().isBlank()) {
                    String idsStr = args.get("articleIds").asText();
                    posts = new java.util.ArrayList<>();
                    for (String id : idsStr.split("[,，]")) {
                        id = id.trim();
                        if (!id.isEmpty()) {
                            try {
                                Post post = client.get(Post.class, id).block();
                                if (post != null) posts.add(post);
                            } catch (Exception e) {
                                log.warn("跳过不存在的文章: {}", id);
                            }
                        }
                    }
                } else {
                    var result = client.list(Post.class, null, null, 0, 200).block();
                    if (result == null) {
                        return "获取文章列表失败";
                    }
                    posts = result.getItems();
                }

                int success = 0;
                int failed = 0;
                for (Post post : posts) {
                    try {
                        var spec = post.getSpec();
                        if (spec == null) continue;
                        if (!tags.isEmpty()) {
                            spec.setTags(tags);
                        }
                        if (!categories.isEmpty()) {
                            spec.setCategories(categories);
                        }
                        client.update(post).block();
                        success++;
                    } catch (Exception e) {
                        log.error("更新文章失败: {}", post.getMetadata().getName(), e);
                        failed++;
                    }
                }

                StringBuilder sb = new StringBuilder();
                sb.append("批量更新完成！共处理 ").append(posts.size()).append(" 篇文章。\n");
                sb.append("- 成功：").append(success).append(" 篇\n");
                if (failed > 0) {
                    sb.append("- 失败：").append(failed).append(" 篇\n");
                }
                if (!tags.isEmpty()) {
                    sb.append("- 标签：").append(String.join(", ", tags)).append("\n");
                }
                if (!categories.isEmpty()) {
                    sb.append("- 分类：").append(String.join(", ", categories)).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                log.error("批量更新文章标签失败", e);
                return "批量更新失败: " + CreateArticleTool.rootMessage(e);
            }
        }
    }

    /**
     * 全自动标签工具 — 读取所有文章标题，调用 AI 自行分类并打上对应标签
                        ? ArticleTool.splitNames(args.get("tags").asText())
                        : List.of();
                List<String> categories = args.has("categories")
                        ? ArticleTool.splitNames(args.get("categories").asText())
                        : List.of();

                if (tags.isEmpty() && categories.isEmpty()) {
                    return "请至少指定 tags 或 categories 参数";
                }

                // 获取目标文章列表
                List<Post> posts;
                if (args.has("articleIds") && !args.get("articleIds").asText().isBlank()) {
                    String idsStr = args.get("articleIds").asText();
                    posts = new java.util.ArrayList<>();
                    for (String id : idsStr.split("[,，]")) {
                        id = id.trim();
                        if (!id.isEmpty()) {
                            try {
                            Post post = client.get(Post.class, id).block();
                                if (post != null) posts.add(post);
                            } catch (Exception e) {
                                log.warn("跳过不存在的文章: {}", id);
                            }
                        }
                    }
                } else {
                    // 获取所有文章
                    var result = client.list(Post.class, null, null, 0, 200).block();
                    if (result == null) {
                        return "获取文章列表失败";
                    }
                    posts = result.getItems();
                }

                // 批量更新
                int success = 0;
                int failed = 0;
                for (Post post : posts) {
                    try {
                        var spec = post.getSpec();
                        if (spec == null) continue;
                        if (!tags.isEmpty()) {
                            spec.setTags(tags);
                        }
                        if (!categories.isEmpty()) {
                            spec.setCategories(categories);
                        }
                        client.update(post).block();
                        success++;
                    } catch (Exception e) {
                        log.error("更新文章失败: {}", post.getMetadata().getName(), e);
                        failed++;
                    }
                }

                StringBuilder sb = new StringBuilder();
                sb.append("批量更新完成！共处理 ").append(posts.size()).append(" 篇文章。\n");
                sb.append("- 成功：").append(success).append(" 篇\n");
                if (failed > 0) {
                    sb.append("- 失败：").append(failed).append(" 篇\n");
                }
                if (!tags.isEmpty()) {
                    sb.append("- 标签：").append(String.join(", ", tags)).append("\n");
                }
                if (!categories.isEmpty()) {
                    sb.append("- 分类：").append(String.join(", ", categories)).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                log.error("批量更新文章标签失败", e);
                return "批量更新失败: " + CreateArticleTool.rootMessage(e);
            }
        }
    }
    /**
     * 全自动标签工具 — 读取所有文章标题，调用 AI 自行分类并打上对应标签，一次调用完成
     */
    public static class AutoTagArticlesTool implements Tool {

        private final ReactiveExtensionClient client;
        private final ReactiveSettingFetcher settingFetcher;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public AutoTagArticlesTool(ReactiveExtensionClient client, ReactiveSettingFetcher settingFetcher) {
            this.client = client;
            this.settingFetcher = settingFetcher;
        }

        @Override
        public String getName() {
            return "autoTagArticles";
        }

        @Override
        public String getDescription() {
            return "自动读取所有文章标题，用 AI 分析并分门别类打上标签，一次调用完成全部操作";
        }

        @Override
        public String getParametersJsonSchema() {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");

            ObjectNode categoriesProp = props.putObject("categories");
            categoriesProp.put("type", "string");
            categoriesProp.put("description", "可选的分类名称，多个用逗号分隔。如果指定，AI 会按这些分类来归类文章；不指定则 AI 自行判断");
            categoriesProp.put("default", "");

            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
            try {
                String userCategories = args.has("categories") ? args.get("categories").asText("") : "";

                // 1. 获取所有文章
                var result = client.list(Post.class, null, null, 0, 200).block();
                if (result == null || result.getItems().isEmpty()) {
                    return "没有找到文章";
                }
                List<Post> posts = result.getItems();

                // 2. 读取 AI 配置
                AiAssistantSetting aiSetting = settingFetcher.fetch("basic", AiAssistantSetting.class)
                        .defaultIfEmpty(new AiAssistantSetting()).block();
                String endpoint = aiSetting.getApiEndpoint();
                String apiKey = aiSetting.getApiKey();
                String model = aiSetting.getModel();
                
                if (apiKey == null || apiKey.isBlank()) {
                    return "错误：未配置 API Key，请先在插件设置中填写";
                }
                if (endpoint == null || endpoint.isBlank()) {
                    endpoint = "https://api.deepseek.com";
                }
                if (model == null || model.isBlank()) {
                    model = "deepseek-chat";
                }

                // 3. 构建 AI 分类 prompt
                StringBuilder promptBuilder = new StringBuilder();
                promptBuilder.append("以下是一个博客的所有文章标题列表，请根据标题内容将它们分组并分配合适的标签。\n\n");
                if (!userCategories.isBlank()) {
                    promptBuilder.append("请按以下分类来归类文章：").append(userCategories).append("\n\n");
                }
                promptBuilder.append("文章列表：\n");
                for (int i = 0; i < posts.size(); i++) {
                    Post post = posts.get(i);
                    String title = post.getSpec() != null && post.getSpec().getTitle() != null
                            ? post.getSpec().getTitle() : "无标题";
                    String id = post.getMetadata().getName();
                    promptBuilder.append(i + 1).append(". [ID: ").append(id).append("] ").append(title).append("\n");
                }
                promptBuilder.append("\n请严格按以下 JSON 格式返回，不要包含其他内容：\n");
                promptBuilder.append("{\n");
                promptBuilder.append("  \"groups\": [\n");
                promptBuilder.append("    {\"tag\": \"标签名\", \"articleIds\": [\"文章ID1\", \"文章ID2\"]},\n");
                promptBuilder.append("    {\"tag\": \"标签名\", \"articleIds\": [\"文章ID3\", \"文章ID4\"]}\n");
                promptBuilder.append("  ]\n");
                promptBuilder.append("}\n");
                promptBuilder.append("\n要求：每个文章只能属于一个组，标签名用中文，控制在 2-4 个字。");

                // 4. 调用 AI API
                Integer maxTokens = aiSetting.getMaxTokens();
                if (maxTokens == null || maxTokens < 1024) maxTokens = 8192;
                String response = callAiApi(endpoint, apiKey, model, promptBuilder.toString(), maxTokens);
                log.info("AI 分类响应: {}", response.substring(0, Math.min(response.length(), 500)));

                // 5. 解析 AI 返回的 JSON
                // 先尝试找到 JSON 起始位置，剥离模型思考过程
                String cleaned = response.trim();
                int jsonStart = cleaned.indexOf("{");
                if (jsonStart > 0) {
                    cleaned = cleaned.substring(jsonStart);
                }
                cleaned = cleaned.trim()
                        .replaceFirst("(?s)^```(?:json)?\\s*", "")
                        .replaceFirst("(?s)\\s*```$", "");
                JsonNode root = objectMapper.readTree(cleaned);
                JsonNode groups = root.path("groups");
                if (!groups.isArray() || groups.size() == 0) {
                    return "AI 返回的分类结果无法解析，请重试";
                }

                // 6. 为每组文章打标签 - 使用已获取的文章列表，避免重复查询
                int totalUpdated = 0;
                int totalFailed = 0;
                StringBuilder detail = new StringBuilder();

                // 建立文章ID到Post的映射，避免逐篇查询
                java.util.Map<String, Post> postMap = new java.util.HashMap<>();
                for (Post post : posts) {
                    postMap.put(post.getMetadata().getName(), post);
                }
                for (JsonNode group : groups) {
                    String tag = group.path("tag").asText("");
                    JsonNode ids = group.path("articleIds");
                    if (tag.isBlank() || !ids.isArray()) continue;

                    List<String> tagList = List.of(tag);
                    int groupSuccess = 0;
                    int groupFailed = 0;

                    for (JsonNode idNode : ids) {
                        String id = idNode.asText("");
                        if (id.isBlank()) continue;
                        try {
                            Post post = postMap.get(id);
                            if (post != null && post.getSpec() != null) {
                                post.getSpec().setTags(tagList);
                                client.update(post).block();
                                groupSuccess++;
                            }
                        } catch (Exception e) {
                            log.warn("更新文章 {} 标签失败: {}", id, e.getMessage());
                            groupFailed++;
                        }
                    }

                    totalUpdated += groupSuccess;
                    totalFailed += groupFailed;
                    if (groupSuccess > 0) {
                        detail.append("\n- 【").append(tag).append("】：").append(groupSuccess).append(" 篇");
                    }
                }

                // 7. 返回结果
                StringBuilder resultBuilder = new StringBuilder();
                resultBuilder.append("✅ 自动分类打标完成！共处理 ").append(posts.size()).append(" 篇文章。\n");
                if (totalUpdated > 0) {
                    resultBuilder.append("成功：").append(totalUpdated).append(" 篇");
                    resultBuilder.append(detail.toString());
                }
                if (totalFailed > 0) {
                    resultBuilder.append("\n失败：").append(totalFailed).append(" 篇");
                }
                return resultBuilder.toString();

            } catch (Exception e) {
                log.error("自动分类打标失败", e);
                return "自动分类打标失败: " + CreateArticleTool.rootMessage(e);
            }
        }

        private String callAiApi(String endpoint, String apiKey, String model, String prompt, Integer maxTokens) {
            String baseUrl = endpoint.replaceAll("/+$", "");
            if (!baseUrl.endsWith("/v1")) {
                baseUrl += "/v1";
            }

            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", model);
                body.put("max_tokens", maxTokens != null && maxTokens > 0 ? maxTokens : 8192);
                ArrayNode messages = body.putArray("messages");
                messages.addObject().put("role", "system")
                        .put("content", "你是一个博客文章分类助手。根据文章标题将文章分组合适的标签。"
                                + "只返回 JSON，不要包含其他内容。");
                messages.addObject().put("role", "user").put("content", prompt);

                java.net.URL url = java.net.URI.create(baseUrl + "/chat/completions").toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                try (var os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                String rawResponse;
                try (var is = conn.getResponseCode() == 200
                        ? conn.getInputStream() : conn.getErrorStream()) {
                    rawResponse = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }

                JsonNode root = objectMapper.readTree(rawResponse);
                return root.path("choices").path(0).path("message").path("content").asText("");
            } catch (Exception e) {
                throw new RuntimeException("调用 AI API 失败: " + e.getMessage(), e);
            }
        }
    }
}
