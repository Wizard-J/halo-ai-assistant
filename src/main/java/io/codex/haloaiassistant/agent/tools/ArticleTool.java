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
            ListResult<Post> resultLocal = result;
            sb.append(String.format("共 %d 篇文章（当前第 %d/%d 页）\n\n",
                    resultLocal.getTotal(), page, resultLocal.getTotalPages()));
            // 数据量大时用精简格式，帮助 AI 高效处理
            boolean compact = resultLocal.getItems().size() > 15;
            if (compact) {
                sb.append("文章列表（精简格式）：\n");
                for (Post post : resultLocal.getItems()) {
                    var meta = post.getMetadata();
                    var spec = post.getSpec();
                    String title = spec != null && spec.getTitle() != null ? spec.getTitle() : "无标题";
                    sb.append(meta.getName()).append(" - ").append(title).append("\n");
                }
            } else {
                sb.append("| ID | 标题 | 状态 | 发布时间 |\n");
                sb.append("|---|---|---|---|\n");
                for (Post post : resultLocal.getItems()) {
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
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取文章列表失败", e);
            return "获取文章列表失败: " + e.getMessage();
        }
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
            ListResult<Post> resultLocal = result;
            sb.append(String.format("共 %d 篇文章（当前第 %d/%d 页）\n\n",
                    resultLocal.getTotal(), page, resultLocal.getTotalPages()));
            // 数据量大时用精简格式，帮助 AI 高效处理
            boolean compact = resultLocal.getItems().size() > 15;
            if (compact) {
                sb.append("文章列表（精简格式）：\n");
                for (Post post : resultLocal.getItems()) {
                    var meta = post.getMetadata();
                    var spec = post.getSpec();
                    String title = spec != null && spec.getTitle() != null ? spec.getTitle() : "无标题";
                    sb.append(meta.getName()).append(" - ").append(title).append("\n");
                }
            } else {
                sb.append("| ID | 标题 | 状态 | 发布时间 |\n");
                sb.append("|---|---|---|---|\n");
                for (Post post : resultLocal.getItems()) {
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
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取文章列表失败", e);
            return "获取文章列表失败: " + e.getMessage();
        }
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
全自动标签工具 — 读取所有文章标题，调用 AI 自行分类并打上对应标签
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
                return "\u26a0\ufe0f 需要确认操作\n\n"
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
