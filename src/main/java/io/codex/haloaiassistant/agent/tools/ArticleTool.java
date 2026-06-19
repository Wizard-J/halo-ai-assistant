package io.codex.haloaiassistant.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codex.haloaiassistant.agent.Tool;
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
        return "获取文章列表，支持分页和状态筛选（已发布、草稿、回收站等）";
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
        sizeProp.put("description", "每页数量");
        sizeProp.put("default", 10);

        ObjectNode statusProp = props.putObject("status");
        statusProp.put("type", "string");
        statusProp.putArray("enum").add("published").add("draft").add("trash").add("");
        statusProp.put("description", "文章状态筛选");
        statusProp.put("default", "");

        return schema.toPrettyString();
    }

    @Override
    public String execute(JsonNode args) {
        int page = args.has("page") ? args.get("page").asInt(1) : 1;
        int size = args.has("size") ? args.get("size").asInt(10) : 10;
        String status = args.has("status") ? args.get("status").asText("") : "";

        try {
            ListResult<Post> result = client.list(Post.class, null, null, page - 1, size).block();
            if (result == null) {
                return "获取文章列表失败：无返回结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("共 %d 篇文章（当前第 %d/%d 页）\n\n",
                    result.getTotal(), page, result.getTotalPages()));
            sb.append("| ID | 标题 | 状态 | 发布时间 |\n");
            sb.append("|---|---|---|---|\n");
            for (Post post : result.getItems()) {
                var meta = post.getMetadata();
                var spec = post.getSpec();
                String title = spec != null && spec.getTitle() != null ? spec.getTitle() : "无标题";
                String postStatus = post.isPublished()
                        ? "已发布" : post.isDeleted()
                        ? "回收站" : "草稿";
                String publishTime = spec != null && spec.getPublishTime() != null
                        ? spec.getPublishTime().toString() : "未设置";
                sb.append(String.format("| %s | %s | %s | %s |\n",
                        meta.getName(), title, postStatus, publishTime));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取文章列表失败", e);
            return "获取文章列表失败: " + e.getMessage();
        }
    }

    @Component
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
            return "更新文章标题、内容、发布状态或发布时间";
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

            schema.putArray("required").add("id");
            return schema.toPrettyString();
        }

        @Override
        public String execute(JsonNode args) {
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
}
